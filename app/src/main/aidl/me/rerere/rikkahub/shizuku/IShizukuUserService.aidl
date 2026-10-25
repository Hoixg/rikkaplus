// Adapted from ExTV/rikkahub-agent, commit a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only
// 1.0 adaptations: local tool approval, current UI APIs and extension navigation.

package me.rerere.rikkahub.shizuku;

// AIDL contract for the process Shizuku spawns under the shell UID (bound via
// Shizuku.bindUserService — Shizuku.newProcess is private in dev.rikka.shizuku:api 13.1.5,
// verified with `javap -p` against the published artifact, so this is the supported way to
// run a command with Shizuku's privileges). See ShizukuManager.kt and ShizukuUserService.kt.
interface IShizukuUserService {

    // Runs `command` under a shell, honoring `timeoutMs`, and returns a JSON object string
    // with stdout / stderr / exit_code (or a structured error on timeout / launch failure).
    // See ShizukuCommandRunner.
    String exec(String command, int timeoutMs) = 1;

    // Short, nonblocking RPCs. Commands run on owned worker threads, never Binder threads.
    String start(String taskId, String requestJson) = 2;
    String read(String taskId) = 3;
    String list() = 4;
    String cancel(String taskId) = 5;
    String forget(String taskId) = 6;
    String output(String taskId, String stream, int offset, int limit, String query) = 7;
    String diagnostics() = 8;

    // Reserved destroy method id required by Shizuku's server contract — it calls this to
    // tear the service down.
    void destroy() = 16777114;
}
