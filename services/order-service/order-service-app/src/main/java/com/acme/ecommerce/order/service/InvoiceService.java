package com.acme.ecommerce.order.service;

import com.acme.ecommerce.order.api.dto.OrderItemResponse;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.ShipToResponse;
import com.acme.ecommerce.order.config.OrderProperties;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Renders a GST-style tax invoice as a PDF.
 *
 * <p>Hand-rolled minimal PDF (monospaced Courier text, base-14 font, no embedded resources):
 * Maven Central was unreachable when this was built so OpenPDF could not be resolved. See tech
 * debt: swap for OpenPDF (the catalog entry is already in libs.versions.toml).
 */
@Service
public class InvoiceService {

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneOffset.UTC);
  private static final int PAGE_W = 595;
  private static final int PAGE_H = 842;
  private static final int MARGIN = 40;
  private static final int SIZE = 9;
  private static final int LEADING = 13;
  private static final int WIDTH_CHARS = 88;

  private record Line(String text, boolean bold, int size) {}

  private final OrderProperties props;

  public InvoiceService(OrderProperties props) {
    this.props = props;
  }

  public static String invoiceNumber(OrderResponse o) {
    return "INV-"
        + o.placedAt().atZone(ZoneOffset.UTC).getYear()
        + "-"
        + o.id().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  public byte[] render(OrderResponse o) {
    List<Line> lines = new ArrayList<>();
    OrderProperties.Seller seller = props.getSeller();
    String rule = "-".repeat(WIDTH_CHARS);

    lines.add(new Line("TAX INVOICE", true, 16));
    lines.add(new Line("", false, SIZE));
    lines.add(new Line("Invoice No : " + invoiceNumber(o), true, SIZE));
    lines.add(new Line("Date       : " + DATE.format(o.placedAt()), false, SIZE));
    lines.add(new Line("Order      : " + o.id(), false, SIZE));
    lines.add(new Line("Payment    : " + o.paymentMethod() + " (" + o.paymentStatus() + ")", false, SIZE));
    lines.add(new Line("", false, SIZE));
    lines.add(new Line("SOLD BY", true, SIZE));
    lines.add(new Line(seller.getName(), false, SIZE));
    lines.add(new Line(seller.getAddress(), false, SIZE));
    lines.add(new Line("GSTIN: " + seller.getGstin(), false, SIZE));
    lines.add(new Line("", false, SIZE));
    ShipToResponse s = o.shipTo();
    lines.add(new Line("BILL TO / SHIP TO", true, SIZE));
    lines.add(new Line(s.fullName() + (o.email() == null ? "" : "  <" + o.email() + ">"), false, SIZE));
    lines.add(new Line(s.line1(), false, SIZE));
    if (s.line2() != null && !s.line2().isBlank()) {
      lines.add(new Line(s.line2(), false, SIZE));
    }
    lines.add(
        new Line(
            s.city() + (s.state() == null ? "" : ", " + s.state()) + " " + s.postalCode()
                + ", " + s.country(),
            false,
            SIZE));
    if (s.phone() != null) {
      lines.add(new Line("Phone: " + s.phone(), false, SIZE));
    }
    lines.add(new Line("", false, SIZE));
    lines.add(new Line(rule, false, SIZE));
    lines.add(new Line(row("Item", "Qty", "Unit price", "Amount"), true, SIZE));
    lines.add(new Line(rule, false, SIZE));
    for (OrderItemResponse i : o.items()) {
      lines.add(
          new Line(
              row(i.name(), String.valueOf(i.quantity()), money(i.unitPrice()), money(i.lineTotal())),
              false,
              SIZE));
    }
    lines.add(new Line(rule, false, SIZE));
    String cur = o.currency().trim();
    lines.add(new Line(total("Subtotal", cur, o.subtotalAmount()), false, SIZE));
    if (o.discountAmount() != null && o.discountAmount().signum() > 0) {
      lines.add(
          new Line(
              total(
                  "Discount" + (o.couponCode() == null ? "" : " (" + o.couponCode() + ")"),
                  cur,
                  o.discountAmount().negate()),
              false,
              SIZE));
    }
    lines.add(new Line(total("Shipping", cur, o.shippingAmount()), false, SIZE));
    lines.add(new Line(total("TOTAL (incl. tax)", cur, o.totalAmount()), true, SIZE));
    lines.add(new Line("", false, SIZE));
    lines.add(new Line("Tax breakdown (included in total)", true, SIZE));
    if (o.taxBreakdown() != null && o.taxBreakdown().igst() != null) {
      lines.add(new Line(total("IGST", cur, o.taxBreakdown().igst()), false, SIZE));
    } else if (o.taxBreakdown() != null) {
      lines.add(new Line(total("CGST", cur, o.taxBreakdown().cgst()), false, SIZE));
      lines.add(new Line(total("SGST", cur, o.taxBreakdown().sgst()), false, SIZE));
    } else {
      lines.add(new Line(o.taxLabel() == null ? "Tax included" : o.taxLabel(), false, SIZE));
    }
    lines.add(new Line(total("Total tax", cur, o.taxAmount()), true, SIZE));
    lines.add(new Line("", false, SIZE));
    lines.add(new Line("This is a computer generated invoice.", false, SIZE));
    return toPdf(lines);
  }

  private static String row(String name, String qty, String unit, String amount) {
    String n = name.length() > 46 ? name.substring(0, 43) + "..." : name;
    return String.format(Locale.ENGLISH, "%-46s %4s %16s %16s", n, qty, unit, amount);
  }

  private static String total(String label, String currency, BigDecimal v) {
    String value = currency + " " + money(v);
    return String.format(Locale.ENGLISH, "%-" + (WIDTH_CHARS - 24) + "s%24s", label, value);
  }

  private static String money(BigDecimal v) {
    return String.format(Locale.ENGLISH, "%,.2f", v == null ? BigDecimal.ZERO : v);
  }

  // ---- minimal PDF writer ------------------------------------------------------------------

  private static byte[] toPdf(List<Line> lines) {
    // Paginate: each page is a content stream of positioned text lines.
    List<String> pages = new ArrayList<>();
    StringBuilder content = new StringBuilder();
    int y = PAGE_H - MARGIN;
    for (Line line : lines) {
      int leading = Math.max(LEADING, line.size() + 4);
      if (y - leading < MARGIN) {
        pages.add(content.toString());
        content = new StringBuilder();
        y = PAGE_H - MARGIN;
      }
      y -= leading;
      content
          .append("BT /")
          .append(line.bold() ? "F2" : "F1")
          .append(' ')
          .append(line.size())
          .append(" Tf ")
          .append(MARGIN)
          .append(' ')
          .append(y)
          .append(" Td (")
          .append(escape(line.text()))
          .append(") Tj ET\n");
    }
    pages.add(content.toString());

    // Object layout: 1 catalog, 2 pages, 3 font regular, 4 font bold, then (page, content) pairs.
    List<String> objects = new ArrayList<>();
    objects.add("<< /Type /Catalog /Pages 2 0 R >>");
    StringBuilder kids = new StringBuilder();
    for (int p = 0; p < pages.size(); p++) {
      kids.append(5 + 2 * p).append(" 0 R ");
    }
    objects.add("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>");
    objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>");
    objects.add(
        "<< /Type /Font /Subtype /Type1 /BaseFont /Courier-Bold /Encoding /WinAnsiEncoding >>");
    for (int p = 0; p < pages.size(); p++) {
      int contentObj = 6 + 2 * p;
      objects.add(
          "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
              + PAGE_W
              + " "
              + PAGE_H
              + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "
              + contentObj
              + " 0 R >>");
      String stream = pages.get(p);
      objects.add(
          "<< /Length " + stream.getBytes(StandardCharsets.ISO_8859_1).length + " >>\nstream\n"
              + stream + "endstream");
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    List<Integer> offsets = new ArrayList<>();
    write(out, "%PDF-1.4\n");
    for (int i = 0; i < objects.size(); i++) {
      offsets.add(out.size());
      write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
    }
    int xref = out.size();
    StringBuilder x = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
    for (int off : offsets) {
      x.append(String.format(Locale.ENGLISH, "%010d 00000 n \n", off));
    }
    x.append("trailer\n<< /Size ")
        .append(objects.size() + 1)
        .append(" /Root 1 0 R >>\nstartxref\n")
        .append(xref)
        .append("\n%%EOF\n");
    write(out, x.toString());
    return out.toByteArray();
  }

  private static void write(ByteArrayOutputStream out, String s) {
    out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
  }

  /** Escapes PDF string delimiters; anything outside Latin-1 becomes '?'. */
  private static String escape(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (char c : s.toCharArray()) {
      if (c == '(' || c == ')' || c == '\\') {
        sb.append('\\').append(c);
      } else if (c < 32 || c > 255) {
        sb.append('?');
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
