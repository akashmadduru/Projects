#!/usr/bin/env bash
# =============================================================================
# Create (and optionally push) a SemVer release tag, enforcing the branch rules.
#
#   infra/scripts/release-tag.sh v1.2.0-rc.1          # on staging
#   infra/scripts/release-tag.sh v1.2.0               # on master
#   infra/scripts/release-tag.sh v1.2.0 --push        # also push the tag
#
# Rules (see docs/guides/branching-and-releases.md):
#   vX.Y.Z-rc.N  only on `staging`  -> CD publishes to the staging environment
#   vX.Y.Z       only on `master`   -> CD publishes to the production environment
# The tag is annotated, the tree must be clean, and the branch must match origin
# so a tag can never point at a commit that is not on GitHub.
# =============================================================================
set -euo pipefail

tag="${1:?usage: release-tag.sh vX.Y.Z[-rc.N] [--push]}"
push="${2:-}"

branch="$(git rev-parse --abbrev-ref HEAD)"

if [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-rc\.[0-9]+$ ]]; then
  required=staging
elif [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  required=master
else
  echo "Invalid tag '$tag'. Use vX.Y.Z (production) or vX.Y.Z-rc.N (staging)." >&2
  exit 1
fi

if [[ "$branch" != "$required" ]]; then
  echo "Tag $tag must be created on '$required' (currently on '$branch')." >&2
  exit 1
fi
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "Working tree has uncommitted changes; commit or stash first." >&2
  exit 1
fi
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  echo "Tag $tag already exists." >&2
  exit 1
fi

git fetch --quiet origin "$required"
if [[ "$(git rev-parse HEAD)" != "$(git rev-parse "origin/$required")" ]]; then
  echo "Local '$required' differs from origin/$required; pull or push first." >&2
  exit 1
fi

git tag -a "$tag" -m "Release $tag"
echo "Created annotated tag $tag on $(git rev-parse --short HEAD)"

if [[ "$push" == "--push" ]]; then
  git push origin "$tag"
  echo "Pushed $tag. The 'CD images' workflow will publish it."
else
  echo "Not pushed. Run: git push origin $tag"
fi
