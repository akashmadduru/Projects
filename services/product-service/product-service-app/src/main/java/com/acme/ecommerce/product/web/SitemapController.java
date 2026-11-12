package com.acme.ecommerce.product.web;

import com.acme.ecommerce.product.service.ProductDiscoveryService;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public {@code /sitemap.xml}: category and product URLs of the storefront. */
@RestController
@RequiredArgsConstructor
public class SitemapController {

  private final ProductDiscoveryService discovery;

  @Value("${product.storefront-base-url:http://localhost:3000}")
  private String baseUrl;

  @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<String> sitemap() {
    String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    xml.append("  <url><loc>").append(escape(base + "/")).append("</loc></url>\n");
    for (UUID id : discovery.activeCategoryIds()) {
      xml.append("  <url><loc>").append(escape(base + "/?categoryId=" + id)).append("</loc></url>\n");
    }
    for (String slug : discovery.activeSlugs()) {
      xml.append("  <url><loc>").append(escape(base + "/p/" + slug)).append("</loc></url>\n");
    }
    xml.append("</urlset>\n");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
        .contentType(MediaType.APPLICATION_XML)
        .body(xml.toString());
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
