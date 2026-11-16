# S3 Image Upload Guide

Product Image Studio now supports staging images locally and uploading them to AWS S3 in batches.

## Workflow

1. **Generate Images** → Images automatically saved to `static/images/`
2. **Review & Verify** → Check metadata and status via API
3. **Upload to S3** → Batch upload all or retry failed uploads
4. **Track Status** → Monitor upload progress

## Local Staging

### Directory Structure
```
static/
├── images/              # Generated JPEGs
│   └── {product_id}.jpg
├── metadata/            # Image metadata with S3 info
│   └── {product_id}.json
└── uploads.jsonl        # S3 upload log
```

### Metadata Format
Each image gets a JSON metadata file with:
```json
{
  "product_id": "uuid",
  "name": "Product Name",
  "file_path": "static/images/uuid.jpg",
  "file_size": 12345,
  "prompt": "studio packshot photo...",
  "negative": "room, interior, furniture...",
  "seed": 3476254715,
  "steps": 20,
  "guidance": 6.5,
  "size": 1024,
  "created_at": "2026-11-16T12:00:00",
  "uploaded": false,
  "s3_url": null,
  "s3_bucket": "my-bucket",
  "s3_key": "products/uuid.jpg"
}
```

## API Endpoints

### Check Static Folder Status
```bash
curl http://127.0.0.1:8765/api/static/status
```

Response:
```json
{
  "static_images": 150,
  "s3_uploaded": 45,
  "s3_failed": 2,
  "s3_pending": 103
}
```

### List Staged Images
```bash
curl "http://127.0.0.1:8765/api/static/images?offset=0&limit=50"
```

### Upload to AWS S3
```bash
curl -X POST http://127.0.0.1:8765/api/s3/upload \
  -H "Content-Type: application/json" \
  -d '{
    "bucket": "my-bucket",
    "prefix": "products/",
    "aws_access_key": "AKIAIOSFODNN7EXAMPLE",
    "aws_secret_key": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    "region": "us-east-1"
  }'
```

Response:
```json
{
  "uploaded": 103,
  "failed": 0,
  "s3_bucket": "my-bucket"
}
```

## Setup Instructions

### 1. AWS Credentials (Choose one)

**Option A: Environment Variables** (Recommended for automation)
```bash
export AWS_ACCESS_KEY_ID=your-access-key
export AWS_SECRET_ACCESS_KEY=your-secret-key
export AWS_DEFAULT_REGION=us-east-1
```

Then upload without credentials in request:
```bash
curl -X POST http://127.0.0.1:8765/api/s3/upload \
  -H "Content-Type: application/json" \
  -d '{"bucket": "my-bucket"}'
```

**Option B: In Request** (For testing)
Include credentials in the POST request body.

### 2. AWS Permissions

Create an IAM user with this policy:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject"
      ],
      "Resource": "arn:aws:s3:::my-bucket/products/*"
    }
  ]
}
```

### 3. Install boto3
```bash
.venv-images\Scripts\pip.exe install boto3
```

## Workflow Example

### 1. Generate 100 images
```bash
curl -X POST http://127.0.0.1:8765/api/queue/next \
  -H "Content-Type: application/json" \
  -d '{"count": 100}'
```

### 2. Monitor progress
```bash
curl http://127.0.0.1:8765/api/static/status
```

### 3. Upload when ready
```bash
curl -X POST http://127.0.0.1:8765/api/s3/upload \
  -H "Content-Type: application/json" \
  -d '{
    "bucket": "my-ecommerce-images",
    "prefix": "products/",
    "aws_access_key": "...",
    "aws_secret_key": "..."
  }'
```

### 4. Verify in S3
All images will be uploaded as:
- `s3://my-ecommerce-images/products/{product_id}.jpg`
- Metadata saved locally in `static/metadata/{product_id}.json` with S3 URLs

## Features

✓ **Automatic Staging** — Images copied to `static/` as they're generated  
✓ **Metadata Tracking** — Full generation details saved per image  
✓ **Batch Upload** — Upload all pending images to S3 in one call  
✓ **Retry Failed** — Automatic retry on S3 upload failures  
✓ **Progress Tracking** — Monitor upload status via API  
✓ **Deferred Upload** — Generate now, upload later  
✓ **Local Archive** — Keep copies for reference/re-upload  

## Troubleshooting

### "boto3 not installed"
```bash
.venv-images\Scripts\pip.exe install boto3
```

### "AWS credentials not provided"
- Set environment variables, or
- Pass credentials in API request body

### S3 upload fails
- Check IAM permissions (needs `s3:PutObject`)
- Verify bucket name is correct
- Check AWS credentials validity
- Review S3 bucket policy

### Images not in static folder
- Check `static/images/` directory exists
- Ensure write permissions for the directory
- Check server logs for errors
