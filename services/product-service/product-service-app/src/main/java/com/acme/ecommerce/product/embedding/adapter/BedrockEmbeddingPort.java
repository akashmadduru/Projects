package com.acme.ecommerce.product.embedding.adapter;

import com.acme.ecommerce.common.core.error.UpstreamUnavailableException;
import com.acme.ecommerce.product.embedding.EmbedKind;
import com.acme.ecommerce.product.embedding.EmbeddingPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

/**
 * {@link EmbeddingPort} over AWS Bedrock {@code InvokeModel}, speaking the Amazon Titan Text
 * Embeddings V2 wire format ({@code inputText}, {@code dimensions}, {@code normalize}).
 *
 * <p>Titan embeds ONE text per call, so a batch fans out over virtual threads bounded by a
 * semaphore; the result order always matches the input order. Credentials are whatever the injected
 * client was built with - in production the SDK default chain (IRSA); this class never sees a key.
 *
 * <p>Vectors are requested L2-normalised, so cosine distance (the index's operator class) and dot
 * product agree.
 */
public final class BedrockEmbeddingPort implements EmbeddingPort {

  private static final String UPSTREAM = "bedrock-embeddings";

  /** Titan V2 accepts up to 8192 tokens; this keeps well inside it for any language. */
  private static final int MAX_INPUT_CHARS = 20_000;

  private static final ObjectMapper JSON = new ObjectMapper();

  private final BedrockRuntimeClient client;
  private final String modelId;
  private final int dims;
  private final Duration queryTimeout;
  private final Duration documentTimeout;
  private final int concurrency;

  public BedrockEmbeddingPort(
      BedrockRuntimeClient client,
      String modelId,
      int dims,
      Duration queryTimeout,
      Duration documentTimeout,
      int concurrency) {
    this.client = client;
    this.modelId = modelId;
    this.dims = dims;
    this.queryTimeout = queryTimeout;
    this.documentTimeout = documentTimeout;
    this.concurrency = Math.max(1, concurrency);
  }

  @Override
  public String modelId() {
    return modelId;
  }

  @Override
  public int dims() {
    return dims;
  }

  @Override
  public List<float[]> embed(List<String> texts, EmbedKind kind) {
    Duration timeout = kind == EmbedKind.QUERY ? queryTimeout : documentTimeout;
    if (texts.size() == 1) {
      return List.of(embedOne(texts.get(0), timeout));
    }
    Semaphore permits = new Semaphore(concurrency);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<float[]>> futures = new ArrayList<>(texts.size());
      for (String text : texts) {
        futures.add(
            executor.submit(
                () -> {
                  permits.acquire();
                  try {
                    return embedOne(text, timeout);
                  } finally {
                    permits.release();
                  }
                }));
      }
      List<float[]> out = new ArrayList<>(texts.size());
      for (Future<float[]> future : futures) {
        out.add(future.get());
      }
      return out;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamUnavailableException(UPSTREAM, e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new UpstreamUnavailableException(UPSTREAM, e.getCause());
    }
  }

  private float[] embedOne(String text, Duration timeout) {
    ObjectNode body = JSON.createObjectNode();
    body.put(
        "inputText", text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text);
    body.put("dimensions", dims);
    body.put("normalize", true);
    try {
      InvokeModelResponse response =
          client.invokeModel(
              InvokeModelRequest.builder()
                  .modelId(modelId)
                  .contentType("application/json")
                  .accept("application/json")
                  .body(SdkBytes.fromUtf8String(body.toString()))
                  .overrideConfiguration(
                      o -> o.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout))
                  .build());
      return parse(response.body().asUtf8String());
    } catch (SdkException | java.io.IOException e) {
      // Message only: never log or surface the input text (it may be a user's query).
      throw new UpstreamUnavailableException(UPSTREAM, e);
    }
  }

  private float[] parse(String json) throws java.io.IOException {
    JsonNode embedding = JSON.readTree(json).get("embedding");
    if (embedding == null || !embedding.isArray() || embedding.size() != dims) {
      throw new java.io.IOException("unexpected embedding payload (expected " + dims + " floats)");
    }
    float[] vector = new float[dims];
    for (int i = 0; i < dims; i++) {
      float value = (float) embedding.get(i).asDouble();
      if (!Float.isFinite(value)) {
        throw new java.io.IOException("non-finite value in embedding");
      }
      vector[i] = value;
    }
    return vector;
  }
}
