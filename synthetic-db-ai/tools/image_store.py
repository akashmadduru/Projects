"""Push a generated product image to the static image server and point the product at it.

Layout on the server: <product_id>/<uuid>.jpg. product.product_images.url and products.thumbnail_url
get the browser-facing URL (IMAGE_PUBLIC_URL). Re-pushing a product overwrites its existing file.
"""
import os
import urllib.request
import uuid

SERVER = os.environ.get("IMAGE_SERVER_URL", "http://127.0.0.1:8090").rstrip("/")
PUBLIC = os.environ.get("IMAGE_PUBLIC_URL", SERVER + "/images").rstrip("/")
TOKEN = os.environ.get("IMAGE_SERVER_TOKEN", "")


def put(key: str, data: bytes, content_type: str = "image/jpeg") -> None:
    req = urllib.request.Request(f"{SERVER}/images/{key}", data=data, method="PUT",
                                 headers={"X-Image-Token": TOKEN, "Content-Type": content_type})
    with urllib.request.urlopen(req, timeout=60) as r:
        if r.status not in (200, 201, 204):
            raise RuntimeError(f"PUT {key} -> {r.status}")


def push(cur, product_id: str, data: bytes) -> str:
    """PUT the jpg and upsert the product's image row (display_order 0). Returns the public URL."""
    cur.execute("SELECT id, url FROM product.product_images WHERE product_id=%s AND url LIKE %s",
                (product_id, PUBLIC + "/%"))
    row = cur.fetchone()
    if row:                                   # redo: overwrite the same file, URL unchanged
        url = row[1]
        put(url[len(PUBLIC) + 1:], data)
    else:
        key = f"{product_id}/{uuid.uuid4()}.jpg"
        put(key, data)
        url = f"{PUBLIC}/{key}"
        cur.execute("DELETE FROM product.product_images WHERE product_id=%s AND url LIKE 'output/images/%%'",
                    (product_id,))
        cur.execute("UPDATE product.product_images SET display_order = display_order + 1 WHERE product_id=%s",
                    (product_id,))
        cur.execute("INSERT INTO product.product_images (product_id, url, display_order) VALUES (%s,%s,0)",
                    (product_id, url))
    cur.execute("UPDATE product.products SET thumbnail_url=%s WHERE id=%s", (url, product_id))
    return url
