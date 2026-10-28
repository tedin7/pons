// transbench: CPU workloads shaped like a grand-strategy game tick.
// Built twice (native arm64 and x86_64) so Box64 settings can be compared
// against native speed on the same device. Prints "name milliseconds" lines.
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {

using Clock = std::chrono::steady_clock;

uint64_t g_sink = 0;  // keeps results alive so the compiler cannot drop the work

// Integer, branchy, memory-bound: shortest paths across a grid of provinces.
uint64_t pathfinding() {
    constexpr int N = 256;
    std::vector<uint8_t> cost(N * N);
    uint32_t seed = 12345;
    for (auto &c : cost) {
        seed = seed * 1103515245u + 12345u;
        c = 1 + (seed >> 24) % 9;
    }
    uint64_t total = 0;
    std::vector<uint32_t> dist(N * N);
    for (int run = 0; run < 24; run++) {
        std::fill(dist.begin(), dist.end(), UINT32_MAX);
        using Node = std::pair<uint32_t, int>;
        std::priority_queue<Node, std::vector<Node>, std::greater<Node>> open;
        int start = (run * 37) % N, goal = N * N - 1 - (run * 53) % N;
        dist[start] = 0;
        open.push({0, start});
        while (!open.empty()) {
            auto [d, at] = open.top();
            open.pop();
            if (at == goal) break;
            if (d != dist[at]) continue;
            int x = at % N, y = at / N;
            const int dx[4] = {1, -1, 0, 0}, dy[4] = {0, 0, 1, -1};
            for (int k = 0; k < 4; k++) {
                int nx = x + dx[k], ny = y + dy[k];
                if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue;
                int next = ny * N + nx;
                uint32_t nd = d + cost[next];
                if (nd < dist[next]) {
                    dist[next] = nd;
                    open.push({nd, next});
                }
            }
        }
        total += dist[goal];
    }
    return total;
}

// Allocation and hashing churn: characters, titles and modifiers keyed by name.
uint64_t economy() {
    std::unordered_map<std::string, double> wealth;
    uint64_t total = 0;
    for (int tick = 0; tick < 40; tick++) {
        for (int i = 0; i < 20000; i++) {
            std::string key = "character_" + std::to_string((i * 7919 + tick) % 30000);
            double &w = wealth[key];
            w = w * 0.99 + (i % 13) * 0.5;
            if (w > 100.0) total += key.size();
        }
        if (tick % 10 == 9) wealth.clear();
    }
    return total + wealth.size();
}

// Floating point and SIMD-friendly loops: map shading.
uint64_t matrix() {
    constexpr int N = 192;
    std::vector<float> a(N * N), b(N * N), c(N * N);
    for (int i = 0; i < N * N; i++) {
        a[i] = (i % 97) * 0.01f;
        b[i] = (i % 89) * 0.02f;
    }
    for (int rep = 0; rep < 20; rep++) {
        for (int i = 0; i < N; i++)
            for (int k = 0; k < N; k++) {
                float aik = a[i * N + k];
                for (int j = 0; j < N; j++) c[i * N + j] += aik * b[k * N + j];
            }
    }
    return static_cast<uint64_t>(c[N + 3] * 1000);
}

// Many short calls into the C math library: statistics and curves.
uint64_t libm() {
    double acc = 0;
    for (int i = 0; i < 8000000; i++) acc += std::sqrt(i * 0.5) * std::exp(-i * 1e-7);
    return static_cast<uint64_t>(acc);
}

// Many small jobs on a locked queue with atomics: the engine's worker pool.
uint64_t threads() {
    unsigned workers = std::max(2u, std::thread::hardware_concurrency());
    std::mutex lock;
    std::condition_variable ready;
    std::queue<int> jobs;
    std::atomic<uint64_t> done{0}, sum{0};
    bool closing = false;
    std::vector<std::thread> pool;
    for (unsigned w = 0; w < workers; w++) {
        pool.emplace_back([&] {
            for (;;) {
                int job;
                {
                    std::unique_lock<std::mutex> guard(lock);
                    ready.wait(guard, [&] { return closing || !jobs.empty(); });
                    if (jobs.empty()) return;
                    job = jobs.front();
                    jobs.pop();
                }
                uint64_t local = 0;
                for (int i = 0; i < 2000; i++) local += (job ^ i) * 2654435761u >> 7;
                sum.fetch_add(local, std::memory_order_relaxed);
                done.fetch_add(1, std::memory_order_release);
            }
        });
    }
    constexpr int kJobs = 200000;
    for (int i = 0; i < kJobs; i++) {
        {
            std::lock_guard<std::mutex> guard(lock);
            jobs.push(i);
        }
        ready.notify_one();
    }
    while (done.load(std::memory_order_acquire) < kJobs) std::this_thread::yield();
    {
        std::lock_guard<std::mutex> guard(lock);
        closing = true;
    }
    ready.notify_all();
    for (auto &t : pool) t.join();
    return sum.load();
}

void run(const char *name, uint64_t (*work)()) {
    auto begin = Clock::now();
    g_sink += work();
    auto ms = std::chrono::duration<double, std::milli>(Clock::now() - begin).count();
    std::printf("%s %.1f\n", name, ms);
    std::fflush(stdout);
}

}  // namespace

int main() {
    run("pathfinding", pathfinding);
    run("economy", economy);
    run("matrix", matrix);
    run("libm", libm);
    run("threads", threads);
    std::fprintf(stderr, "checksum %llu\n", static_cast<unsigned long long>(g_sink));
    return 0;
}
