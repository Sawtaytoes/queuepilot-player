# The public GitHub copy is pushed from Forgejo, and every commit uses the no-reply address

**Status:** Accepted

**Date:** 2026-11-25

**Type:** Repository and publishing

**Supersedes:** None

**Superseded by:** None

## Decision

1. **The repository is public at <https://github.com/Sawtaytoes/queuepilot-player>.** Forgejo
   stays the working repository. Pull requests, CI, reviews, and merges happen on Forgejo.
2. **`.forgejo/workflows/publish.yml` pushes `main` and the tags to GitHub** after every push
   to Forgejo's `main` and every tag push. It never publishes topic branches or pull request
   refs, and it never force-pushes.
3. **Every commit uses `3948069+Sawtaytoes@users.noreply.github.com`** as author and
   committer. The job fails, and prints only the commit IDs, when a new commit uses any other
   address. Set it in every checkout before committing:

   ```sh
   git config user.name Sawtaytoes
   git config user.email 3948069+Sawtaytoes@users.noreply.github.com
   ```

4. **Merges are fast-forward only.** Rebase and squash the branch locally on `origin/main`,
   push it, wait for CI, and merge with the `fast-forward-only` style. The repository allows no
   other merge style and no update by rebase. A merge commit, a squash, or a rebase that
   Forgejo makes uses the Forgejo account's email, and the publish job would refuse it.
5. **The job pushes with a deploy key** stored in the `PUBLISH_DEPLOY_KEY` Actions secret. The
   key can write only this GitHub repository.

## Context

The repository was private on Forgejo until 2026-11-25. Before publication, a scan of every
commit found no household detail, no credentials, and no keystore. OCR of every committed
screenshot found only the invented fixture profiles. The only personal value was the owner's
personal email address on all 53 commits. Before the first push to GitHub, the history was
rewritten with `git filter-repo --mailmap`. Author and committer names became `Sawtaytoes`,
and every address became the GitHub no-reply address. No file content changed. Commit IDs
changed, so a commit ID quoted in an older document can point to a commit that no longer
exists.

## Why

- **A Forgejo push mirror did not work.** Its SSH mode failed with
  `No user exists for uid 568`, because the Forgejo server runs as a user ID that has no
  passwd entry. Its HTTPS mode would store a GitHub token that can write every repository of
  the account. A deploy key limits the credential to one repository.
- **Publishing only `main` and the tags** keeps work in progress private until a reviewed
  change merges.
- **The address check is in the job, not only in the instructions**, because a fresh clone
  uses whatever identity its machine has. Without the check, a single commit would publish a
  personal address that a later rewrite could not remove from other people's clones.
- **Fast-forward merges create no new commit**, so the identity on `main` is the identity the
  author committed with.

## Evidence

- `bin/check-app-repo-for-personal-detail` on an anonymous clone of the GitHub repository on
  2026-11-25: clean across 813 blobs, with one ref, `refs/heads/main`.
- The same clone's `git log --all --format='%ae%n%ce'` printed only the no-reply address.
