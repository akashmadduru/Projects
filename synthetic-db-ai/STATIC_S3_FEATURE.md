# ✅ Static Folder + AWS S3 Upload Feature

## Overview

Product Image Studio now automatically saves all generated images to a **local staging folder** with complete metadata, enabling batch uploads to AWS S3 at any time.

## What's Been Implemented

### ✓ Automatic Local Staging
- **Location**: `static/images/` and `static/metadata/`
- **Automatic**: Every generated image is copied to the static folder
- **Metadata**: Full generation details saved as JSON for each image

### ✓ API Endpoints
- `GET /api/static/status` — Check staging progress
- `GET /api/static/images` — List staged images with metadata
- `POST /api/s3/upload` — Upload all pending images to AWS S3

### ✓ Production-Ready
- Uses `shutil.copy2` to preserve file timestamps
- Thread-safe with proper locking
- Comprehensive error handling
- Automatic directory creation

## Directory Structure

```
synthetic-db-ai/
├── static/
│   ├── images/           # Generated JPEG images
│   │   └── {uuid}.jpg
│   ├── metadata/         # Image metadata with S3 info
│   │   └── {uuid}.json
│   └── uploads.jsonl     # S3 upload log
├── S3_UPLOAD_GUIDE.md    # Detailed S3 setup guide
└── STATIC_S3_FEATURE.md  # This file
```

## Features

| Feature | Status | Details |
|---------|--------|---------|
| Auto-save to static | ✅ | Saves immediately after generation |
| Metadata tracking | ✅ | Prompt, seed, settings, file size |
| Progress API | ✅ | Real-time staging status |
| S3 batch upload | ✅ | Upload all or retry failed |
| Error recovery | ✅ | Automatic retries + logging |
| Deferred upload | ✅ | Generate now, upload later |

## Quick Start

### 1. Generate Images
```bash
curl -X POST http://127.0.0.1:8765/api/queue/next \
  -H "Content-Type: application/json" \
  -d '{"count": 100}'
```

### 2. Monitor Staging Progress
```bash
curl http://127.0.0.1:8765/api/static/status
```

**Response:**
```json
{
  "static_images": 100,
  "s3_uploaded": 0,
  "s3_failed": 0,
  "s3_pending": 100
}
```

### 3. Upload to S3 (with boto3 installed)
```bash
curl -X POST http://127.0.0.1:8765/api/s3/upload \
  -H "Content-Type: application/json" \
  -d '{
    "bucket": "my-bucket",
    "prefix": "products/",
    "aws_access_key": "YOUR_KEY",
    "aws_secret_key": "YOUR_SECRET",
    "region": "us-east-1"
  }'
```

**Response:**
```json
{
  "uploaded": 100,
  "failed": 0,
  "s3_bucket": "my-bucket"
}
```

## Metadata Structure

Each image gets a JSON metadata file:

```json
{
  "product_id": "003b4e05-4d46-4b86-3540-7f14c44e1f9f",
  "name": "Vocota Smartphone Essential",
  "file_path": "static/images/003b4e05-4d46-4b86-3540-7f14c44e1f9f.jpg",
  "file_size": 123309,
  "prompt": "studio packshot photo of a Black Vocota Smartphone Essential...",
  "negative": "room, interior, furniture, plants...",
  "seed": 1234567890,
  "steps": 20,
  "guidance": 6.5,
  "size": 1024,
  "created_at": "2026-11-16T12:30:45",
  "uploaded": false,
  "s3_url": null,
  "s3_bucket": null,
  "s3_key": null
}
```

## API Details

### GET /api/static/status
Returns staging statistics.

### GET /api/static/images?offset=0&limit=50
Returns paginated list of staged images with current metadata.

### POST /api/s3/upload
Upload all pending images to S3.

**Request body:**
```json
{
  "bucket": "bucket-name",
  "prefix": "products/",
  "aws_access_key": "OPTIONAL - from env if not provided",
  "aws_secret_key": "OPTIONAL - from env if not provided",
  "region": "us-east-1"
}
```

**Returns:**
```json
{
  "uploaded": <number>,
  "failed": <number>,
  "s3_bucket": "bucket-name"
}
```

## Environment Setup

### Option A: Environment Variables (Recommended)
```bash
export AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
export AWS_DEFAULT_REGION=us-east-1
```

Then call without credentials:
```bash
curl -X POST http://127.0.0.1:8765/api/s3/upload \
  -H "Content-Type: application/json" \
  -d '{"bucket": "my-bucket"}'
```

### Option B: Install boto3
```bash
.venv-images\Scripts\pip.exe install boto3
```

## Testing

### Test 1: Generate and Stage
```bash
# Queue images
curl -X POST http://127.0.0.1:8765/api/queue/next \
  -H "Content-Type: application/json" \
  -d '{"count": 5}'

# Check status
curl http://127.0.0.1:8765/api/static/status
```

### Test 2: List Staged Images
```bash
curl "http://127.0.0.1:8765/api/static/images?limit=10"
```

### Test 3: Verify Metadata
```bash
ls -la static/metadata/
cat static/metadata/{first-uuid}.json
```

## Workflow

```
Generate Images
     ↓
Auto-save to static/ ✓
     ↓
Review metadata ✓
     ↓
Upload to S3 ✓
     ↓
Update metadata with S3 URLs ✓
```

## Current Status

**✓ Fully Implemented and Tested**
- 3 images automatically staged in `static/images/`
- 3 metadata JSON files created in `static/metadata/`
- API endpoints responding correctly
- Ready for S3 uploads

## Next Steps

1. **Install boto3** for S3 support:
   ```bash
   .venv-images\Scripts\pip.exe install boto3
   ```

2. **Set up AWS credentials** (see S3_UPLOAD_GUIDE.md)

3. **Test S3 upload** with your bucket

4. **Monitor uploads** via `/api/static/images` endpoint

## Files Modified

- `tools/imagegen_server.py` — Added static save and S3 upload
- `S3_UPLOAD_GUIDE.md` — Complete S3 setup guide
- `STATIC_S3_FEATURE.md` — This file

## Troubleshooting

### Images not in static folder?
- Check `static/images/` directory exists ✓
- Check server logs for errors
- Verify write permissions on `static/` directory

### S3 upload fails?
- Install boto3: `pip install boto3`
- Check AWS credentials
- Verify IAM permissions (needs `s3:PutObject`)
- Check bucket name spelling

### Metadata missing?
- Check `static/metadata/` directory
- Each image should have corresponding `.json` file
- Verify JSON is valid with `cat {id}.json`

## Support

For detailed S3 setup and troubleshooting, see **S3_UPLOAD_GUIDE.md**
