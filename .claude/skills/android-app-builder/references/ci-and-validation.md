# CI as compiler: repo setup, workflow, reading results

## Repo and access
- `mcp__github__create_repository` returned `403 Resource not accessible by integration` → the user creates the repo (empty is fine, private is fine).
- `add_repo` fails with "not found / no access" until the Claude GitHub App is allowed for that repo (user: GitHub → Settings → Applications → Claude → Repository access). Retry `add_repo` afterwards with `access: "push"`, then clone **once** (`git clone --depth 1`), then `register_repo_root`.
- A fresh repo is empty: `git checkout -b <session-branch>`, commit, `git push -u origin <session-branch>`. That push creates the branch. Do not create PRs unless asked.
- Commit trailer from the session reminder (Co-Authored-By + Claude-Session) on every commit.

## Workflow file (verified, see assets/reference-app/.github/workflows/build.yml)
Order of steps matters:
1. `actions/checkout@v4`, `actions/setup-java@v4` (temurin 17), `gradle/actions/setup-gradle@v4` with `gradle-version: "8.11.1"`.
2. `gradle --no-daemon lintDebug assembleDebug` (lint is the code validation).
3. Release build (`assembleRelease`) — optional keystore from secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, decoded to `$RUNNER_TEMP`, exported as `KEYSTORE_FILE`.
4. **Size report must run after the release build** (my first attempt ran before it and failed with "no such file").
5. Upload artifacts; tag `v*` → `softprops/action-gh-release@v2` attaches the release APK.

Pitfalls seen:
- `android-actions/setup-android@v3` crashed on the Node 24 runner. `ubuntu-latest` already ships the SDK (`$ANDROID_HOME`, `cmdline-tools/latest/bin/apkanalyzer`, build-tools) — just don't install anything.
- No Gradle wrapper in the repo is fine with `setup-gradle` + `gradle-version`; call plain `gradle`.
- `if: always()` on report/lint-artifact steps so failures still leave evidence.

## Reading results with the GitHub tools (cheap → expensive)
1. `actions_list list_workflow_runs` (`perPage: 1`) → status/conclusion of the newest run. Wait for CI with `sleep 200–230` in Bash, not by polling in a tight loop.
2. `actions_list list_workflow_jobs` → step names, conclusions. **Trick:** put numbers into a step name (`name: "Release APK: ${{ env.APK_BYTES }} bytes"`, value written earlier via `echo "APK_BYTES=…" >> $GITHUB_ENV`). They show up here with zero log reading.
3. `get_job_logs` with `return_content: true` and a `tail_lines` just big enough. The signed log URL (blob storage) is blocked by the egress proxy, so `curl` on it fails with 403. Post-job steps (setup-gradle cleanup) add ~100 noise lines at the end, so when you need a report block either print it compactly or count ~230 lines back. Gradle/Kotlin compile errors appear as `e: file:///…/MainActivity.kt:LINE:COL message`.
4. `list_workflow_run_artifacts` shows artifact zip sizes (zip of the APK, slightly smaller than the APK).

For experiments (A/B size matrix) a throw-away `workflow_dispatch`/path-filtered-push workflow with a job matrix + a final `report` job that downloads tiny result artifacts and prints one table (no Gradle in that job → short log tail) worked well; delete it afterwards.
