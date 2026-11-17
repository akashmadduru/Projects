"""Link a generated product image to its product, optionally uploading it to static-image-server.

DB rows store the KEY (<product_id>.jpg), not a URL: product-service resolves it against the
runtime parentUrl setting, so moving the image host never needs a data migration.
static-image-server copies product-images/images/*.jpg into itself on every start, so the upload
is only needed when the server cannot see that folder.
"""
import os
import urllib.request

SERVER = os.environ.get("STATIC_IMAGE_SERVER_URL", "http://127.0.0.1:8095/product-images").rstrip("/")
TOKEN = os.environ.get("IMAGE_SERVER_TOKEN", "")


def put(key: str, data: bytes, content_type: str = "image/jpeg") -> None:
    req = urllib.request.Request(f"{SERVER}/{key}", data=data, method="PUT",
                                 headers={"X-Image-Token": TOKEN, "Content-Type": content_type})
    with urllib.request.urlopen(req, timeout=60) as r:
        if r.status not in (200, 201, 204):
            raise RuntimeError(f"PUT {key} -> {r.status}")


def link(cur, product_id: str, data: bytes | None = None) -> str:
    """Make <product_id>.jpg the product's first image and thumbnail (idempotent). Returns the key."""
    key = f"{product_id}.jpg"
    if data is not None:
        put(key, data)
    cur.execute("SELECT 1 FROM product.product_images WHERE product_id=%s AND url=%s", (product_id, key))
    if cur.fetchone() is None:
        # Earlier tooling stored the file path; that row becomes the key row.
        cur.execute("DELETE FROM product.product_images WHERE product_id=%s AND url LIKE 'output/images/%%'",
                    (product_id,))
        cur.execute("UPDATE product.product_images SET display_order = display_order + 1 WHERE product_id=%s",
                    (product_id,))
        cur.execute("INSERT INTO product.product_images (product_id, url, display_order) VALUES (%s,%s,0)",
                    (product_id, key))
    cur.execute("UPDATE product.products SET thumbnail_url=%s WHERE id=%s", (key, product_id))
    return key
