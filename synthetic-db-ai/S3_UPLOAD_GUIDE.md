# Product images: local folders and S3 upload

Every image the studio generates is saved in three places, each with a metadata JSON of the same name:

| folder | purpose |
|---|---|
| `output/images/` | generator output and the source of truth for "done" (plus `manifest.jsonl`) |
| `product-images/images/` + `metadata/` | long-term local copy |
| `static/images/` + `metadata/` | staging area for S3; metadata carries `uploaded`, `s3_url`, `s3_bucket`, `s3_key` |

Metadata fields: `product_id`, `name`, `prompt` (the rendered prompt actually used), `negative`, `seed`, `steps`,
`guidance`, `size`, `created_at`, `file_path` (forward slashes, relative to the project root), `file_size`.

## Upload to S3 later

1. `.venv-images\Scripts\pip install boto3`
2. Provide AWS credentials the standard way (environment variables, `aws configure` profile, or a role) before
   starting the studio, e.g. `set AWS_ACCESS_KEY_ID=...`, `set AWS_SECRET_ACCESS_KEY=...`, `set AWS_DEFAULT_REGION=...`.
   Credentials are never accepted in the request. The IAM user needs `s3:PutObject` on the target prefix.
3. Check what is staged: `curl http://127.0.0.1:8765/api/static/status`
4. Upload everything not yet uploaded:

```bash
curl -X POST http://127.0.0.1:8765/api/s3/upload -H "Content-Type: application/json" \
  -d '{"bucket": "my-bucket", "prefix": "products/"}'
```

Result: `{"uploaded": N, "failed": M, "s3_bucket": "..."}`. Objects are stored as `<prefix><product_id>.jpg`.

- Upload state is kept in each `static/metadata/<id>.json` (`uploaded: true`), so it survives restarts and re-running the
  call only uploads what is new or failed. Regenerating an image (Redo) resets its flag so the new version is uploaded.
- `GET /api/static/images?offset=0&limit=50` lists staged images with their metadata and upload status.
- Each upload run is appended to `static/uploads.jsonl`.
