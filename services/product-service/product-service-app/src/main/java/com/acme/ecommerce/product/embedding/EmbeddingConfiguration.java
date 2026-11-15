package com.acme.ecommerce.product.embedding;

import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.product.embedding.adapter.BedrockEmbeddingPort;
import com.acme.ecommerce.product.embedding.adapter.FakeEmbeddingPort;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Wires the embedding provider - or, deliberately, nothing.
 *
 * <p>{@code product.embedding.provider=none} (the default) registers NO {@link EmbeddingPort}.
 * Every consumer treats the missing bean as "feature disabled" and degrades to keyword search;
 * there is no "fake in normal runs" fallback anywhere.
 *
 * <p>AWS credentials are never configured here. {@link DefaultCredentialsProvider} is the SDK's
 * default chain: IRSA / web identity on EKS, the standard AWS_* variables or a profile locally.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfiguration {

  private static final Set<String> PROVIDERS =
      Set.of(
          EmbeddingProperties.PROVIDER_NONE,
          EmbeddingProperties.PROVIDER_BEDROCK,
          EmbeddingProperties.PROVIDER_FAKE);

  /**
   * Fails the start-up on a configuration that would otherwise silently disable search or write
   * wrong-width vectors: an unknown provider, a fake provider outside test/dev-fake, a vector width
   * that does not match the column, or Bedrock without a region.
   */
  @Bean
  public InitializingBean embeddingConfigurationValidator(
      EmbeddingProperties properties, Environment environment) {
    return () -> {
      String provider = properties.getProvider();
      if (!PROVIDERS.contains(provider)) {
        throw new IllegalStateException(
            "product.embedding.provider must be one of "
                + PROVIDERS
                + " but was '"
                + provider
                + "'");
      }
      if (EmbeddingProperties.PROVIDER_NONE.equals(provider)) {
        return;
      }
      if (properties.getDims() != EmbeddingSchema.DIMS) {
        throw new IllegalStateException(
            "product.embedding.dims="
                + properties.getDims()
                + " does not match the vector("
                + EmbeddingSchema.DIMS
                + ") column; changing the dimension needs a new embeddings table, not a setting");
      }
      if (EmbeddingProperties.PROVIDER_FAKE.equals(provider)
          && !environment.acceptsProfiles(Profiles.of("test", "dev-fake"))) {
        throw new IllegalStateException(
            "product.embedding.provider=fake is only allowed under the 'test' or 'dev-fake' profile");
      }
      if (EmbeddingProperties.PROVIDER_BEDROCK.equals(provider)
          && properties.getBedrock().getRegion().isBlank()) {
        throw new IllegalStateException(
            "product.embedding.bedrock.region (AWS_REGION) is required when provider=bedrock");
      }
    };
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "product.embedding", name = "provider", havingValue = "bedrock")
  public BedrockRuntimeClient bedrockRuntimeClient(EmbeddingProperties properties) {
    return BedrockRuntimeClient.builder()
        .region(Region.of(properties.getBedrock().getRegion()))
        .credentialsProvider(DefaultCredentialsProvider.builder().build())
        .build();
  }

  @Bean
  @ConditionalOnProperty(prefix = "product.embedding", name = "provider", havingValue = "bedrock")
  public EmbeddingPort bedrockEmbeddingPort(
      BedrockRuntimeClient client, EmbeddingProperties properties) {
    return new BedrockEmbeddingPort(
        client,
        properties.getBedrock().getModelId(),
        properties.getDims(),
        properties.getQueryTimeout(),
        properties.getDocumentTimeout(),
        properties.getReconciler().getDocumentConcurrency());
  }

  /** Test and explicit dev opt-in only; the validator above rejects it under any other profile. */
  @Bean
  @Profile({"test", "dev-fake"})
  @ConditionalOnProperty(prefix = "product.embedding", name = "provider", havingValue = "fake")
  public EmbeddingPort fakeEmbeddingPort(EmbeddingProperties properties) {
    return new FakeEmbeddingPort(properties.getDims());
  }

  @Bean
  public QueryEmbeddingCache queryEmbeddingCache(
      EmbeddingProperties properties, TimeProvider timeProvider) {
    return new QueryEmbeddingCache(
        properties.getCache().getMaxEntries(), properties.getCache().getTtl(), timeProvider);
  }
}
