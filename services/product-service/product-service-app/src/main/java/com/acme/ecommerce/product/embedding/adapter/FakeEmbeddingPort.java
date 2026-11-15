package com.acme.ecommerce.product.embedding.adapter;

import com.acme.ecommerce.product.embedding.EmbedKind;
import com.acme.ecommerce.product.embedding.EmbeddingPort;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for a real embedding model. TESTS AND THE EXPLICIT {@code dev-fake}
 * PROFILE ONLY - see {@code EmbeddingConfiguration}, which refuses to register it anywhere else.
 *
 * <p>Not random noise: each word contributes a fixed pseudo-random direction (seeded from the
 * SHA-256 of the word) and the text vector is the normalised sum, so two texts sharing words have a
 * higher cosine similarity than two that share none. That makes ranking assertions in tests
 * meaningful while staying 100% reproducible across JVMs and runs. It has NO semantic knowledge
 * ("laptop" is not near "notebook").
 */
public final class FakeEmbeddingPort implements EmbeddingPort {

  public static final String MODEL_ID = "fake-hash-embedding-v1";

  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

  private final int dims;

  public FakeEmbeddingPort(int dims) {
    this.dims = dims;
  }

  @Override
  public String modelId() {
    return MODEL_ID;
  }

  @Override
  public int dims() {
    return dims;
  }

  @Override
  public List<float[]> embed(List<String> texts, EmbedKind kind) {
    List<float[]> out = new ArrayList<>(texts.size());
    for (String text : texts) {
      out.add(embedOne(text));
    }
    return out;
  }

  private float[] embedOne(String text) {
    double[] sum = new double[dims];
    Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
    boolean any = false;
    while (matcher.find()) {
      any = true;
      SplittableRandom random = new SplittableRandom(seedOf(matcher.group()));
      for (int i = 0; i < dims; i++) {
        sum[i] += random.nextDouble() * 2.0 - 1.0;
      }
    }
    if (!any) {
      SplittableRandom random = new SplittableRandom(seedOf(""));
      for (int i = 0; i < dims; i++) {
        sum[i] = random.nextDouble() * 2.0 - 1.0;
      }
    }
    double norm = 0;
    for (double v : sum) {
      norm += v * v;
    }
    norm = Math.sqrt(norm);
    float[] vector = new float[dims];
    for (int i = 0; i < dims; i++) {
      vector[i] = (float) (sum[i] / norm);
    }
    return vector;
  }

  private static long seedOf(String word) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(word.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }
}
