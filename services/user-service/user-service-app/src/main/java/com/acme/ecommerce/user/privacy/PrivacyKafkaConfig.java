package com.acme.ecommerce.user.privacy;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import com.acme.ecommerce.user.config.InternalSecurityConfig;
import java.time.Duration;
import org.springframework.cloud.client.loadbalancer.LoadBalancerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

/** Producer for {@code user.events} plus the load-balanced client used for /internal/privacy calls. */
@Configuration(proxyBeanMethods = false)
public class PrivacyKafkaConfig {

  @Bean
  public KafkaTemplate<String, String> privacyKafkaTemplate(
      @Value("${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}") String servers) {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
    return new KafkaTemplate<>(
        new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer()));
  }

  /**
   * Load-balanced HTTP for /internal/privacy calls. Deliberately a wrapper type, NOT a RestClient.Builder or
   * RestTemplate bean: Eureka's own transport picks those up and would try to resolve eureka-server
   * through the load balancer.
   */
  @Bean
  public PrivacyHttp privacyHttp(LoadBalancerInterceptor loadBalancer) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(15));
    RestTemplate rt = new RestTemplate(factory);
    rt.getInterceptors().add(loadBalancer);
    return new PrivacyHttp(rt);
  }

  public record PrivacyHttp(RestTemplate rest) {
    public String get(String url, String token, Object... vars) {
      HttpHeaders h = new HttpHeaders();
      h.set(InternalSecurityConfig.HEADER, token);
      return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class, vars).getBody();
    }

    public String postJson(String url, String token, String json, Object... vars) {
      HttpHeaders h = new HttpHeaders();
      h.set(InternalSecurityConfig.HEADER, token);
      h.setContentType(MediaType.APPLICATION_JSON);
      return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(json, h), String.class, vars)
          .getBody();
    }
  }
}
