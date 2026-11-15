package com.acme.ecommerce.product.embedding;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Tunables of the semantic-search feature (Cartwise AI-1).
 *
 * <p>JavaBean binding with field-initializer defaults, like {@code ProductProperties}. NOTHING HERE
 * IS A SECRET: AWS credentials come from the SDK's default provider chain (IRSA on EKS, or the
 * standard AWS_* environment variables / profile locally), never from configuration.
 */
@Data
@ConfigurationProperties(prefix = "product.embedding")
public class EmbeddingProperties {

  /** Provider values accepted by {@link #provider}. */
  public static final String PROVIDER_NONE = "none";

  public static final String PROVIDER_BEDROCK = "bedrock";
  public static final String PROVIDER_FAKE = "fake";

  /**
   * {@code none} (default): feature disabled, search is keyword-only. {@code bedrock}: AWS Bedrock.
   * {@code fake}: deterministic hash embeddings, honoured ONLY under the {@code test} or {@code
   * dev-fake} Spring profile.
   */
  private String provider = PROVIDER_NONE;

  /** Vector width. Must equal {@link EmbeddingSchema#DIMS}; checked at startup. */
  private int dims = EmbeddingSchema.DIMS;

  /** Total time budget for embedding ONE search query (SDK call timeout). */
  private Duration queryTimeout = Duration.ofMillis(800);

  /** Total time budget for one document embedding call (reconciler). */
  private Duration documentTimeout = Duration.ofSeconds(10);

  @NestedConfigurationProperty private Bedrock bedrock = new Bedrock();
  @NestedConfigurationProperty private Cache cache = new Cache();
  @NestedConfigurationProperty private Search search = new Search();
  @NestedConfigurationProperty private Reconciler reconciler = new Reconciler();

  /** Bedrock adapter settings. */
  @Data
  public static class Bedrock {
    /** AWS region of the Bedrock endpoint. Required when provider=bedrock. */
    private String region = "";

    /** Embedding model; the adapter speaks the Titan Text Embeddings V2 request format. */
    private String modelId = "amazon.titan-embed-text-v2:0";
  }

  /** Query-embedding cache. */
  @Data
  public static class Cache {
    private Duration ttl = Duration.ofMinutes(5);
    private int maxEntries = 10_000;
  }

  /** Hybrid ranking knobs. */
  @Data
  public static class Search {
    /** Reciprocal Rank Fusion constant. */
    private int rrfK = 60;

    /**
     * Candidates taken from EACH leg before fusion. The fused set therefore holds at most {@code 2
     * * topN} ids and that is the hard ceiling on {@code totalElements} of a search response.
     */
    private int topN = 100;

    /** Cosine distance above which a vector neighbour is junk and dropped. Tune on a judged set. */
    private double maxDistance = 0.65;

    /** {@code pg_trgm.similarity_threshold} for the keyword leg's indexed {@code %} operator. */
    private double trigramThreshold = 0.25;

    /** {@code hnsw.ef_search} for the vector leg. */
    private int hnswEfSearch = 100;
  }

  /** Background reconciler. */
  @Data
  public static class Reconciler {
    private boolean enabled = true;

    /** Products read, embedded and written per round trip. */
    private int batchSize = 64;

    /** Upper bound of rounds per scheduled run, so one run cannot monopolise a replica. */
    private int maxBatchesPerRun = 50;

    /** A product not verified for this long is re-hashed (catches category renames). */
    private Duration fullSweepInterval = Duration.ofHours(24);

    /** First retry delay after a provider failure; doubles per attempt up to {@code backoffMax}. */
    private Duration backoffBase = Duration.ofMinutes(1);

    private Duration backoffMax = Duration.ofHours(6);

    /** Concurrent provider calls inside one document batch. */
    private int documentConcurrency = 4;
  }
}
