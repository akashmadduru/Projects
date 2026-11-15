package com.acme.ecommerce.product.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The text that represents a product to the embedding model, and the hash that says whether it has
 * changed.
 *
 * <p>What goes in: name, brand, category name, description (truncated) and the flattened {@code
 * attributes} and {@code specifications} JSON. What stays OUT: price, stock and ratings - they
 * change constantly, add noise to the vector and would make every rating event look like a content
 * change.
 *
 * <p>Pure and deterministic: the same inputs always yield the same text, so {@link #hash} is a
 * reliable change detector.
 */
public final class EmbeddingText {

  /** Longest description kept; the rest adds cost, not signal. */
  static final int MAX_DESCRIPTION_CHARS = 2_000;

  private static final ObjectMapper JSON = new ObjectMapper();

  private EmbeddingText() {}

  /**
   * Builds the document text.
   *
   * @param attributesJson the {@code attributes} jsonb as text (nullable)
   * @param specificationsJson the {@code specifications} jsonb as text (nullable)
   */
  public static String build(
      String name,
      String brand,
      String categoryName,
      String description,
      String attributesJson,
      String specificationsJson) {
    List<String> lines = new ArrayList<>();
    addLine(lines, "Name", name);
    addLine(lines, "Brand", brand);
    addLine(lines, "Category", categoryName);
    String desc = normalize(description);
    if (desc.length() > MAX_DESCRIPTION_CHARS) {
      desc = desc.substring(0, MAX_DESCRIPTION_CHARS);
    }
    addLine(lines, "Description", desc);
    lines.addAll(flatten(attributesJson));
    for (String line : flatten(specificationsJson)) {
      if (!lines.contains(line)) {
        lines.add(line);
      }
    }
    return String.join("\n", lines);
  }

  /** {@code sha256(text + "|" + modelId)} as 64 lower-case hex characters. */
  public static String hash(String text, String modelId) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((text + "|" + modelId).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }

  private static void addLine(List<String> lines, String label, String value) {
    String normalized = normalize(value);
    if (!normalized.isEmpty()) {
      lines.add(label + ": " + normalized);
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip().replaceAll("\\s+", " ");
  }

  /** {@code key: value} lines, keys sorted, nested keys dotted, arrays comma-joined. */
  private static List<String> flatten(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (java.io.IOException e) {
      // Unreadable JSON contributes nothing; the rest of the text still embeds.
      return List.of();
    }
    Map<String, String> flat = new TreeMap<>();
    collect("", root, flat);
    List<String> lines = new ArrayList<>(flat.size());
    flat.forEach(
        (key, value) -> {
          String normalized = normalize(value);
          if (!normalized.isEmpty()) {
            lines.add(key + ": " + normalized);
          }
        });
    return lines;
  }

  private static void collect(String prefix, JsonNode node, Map<String, String> out) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        String key = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
        collect(key, field.getValue(), out);
      }
    } else if (node.isArray()) {
      List<String> parts = new ArrayList<>();
      for (JsonNode element : node) {
        if (element.isValueNode()) {
          parts.add(element.asText());
        }
      }
      if (!prefix.isEmpty() && !parts.isEmpty()) {
        out.put(prefix, String.join(", ", parts));
      }
    } else if (!prefix.isEmpty()) {
      out.put(prefix, node.asText());
    }
  }
}
