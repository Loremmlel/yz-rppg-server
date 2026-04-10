import argparse
import csv

import matplotlib.pyplot as plt


def read_rows(path):
    with open(path, "r", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def to_float(rows, key):
    return [float(r[key]) for r in rows]


def to_int(rows, key):
    return [int(r[key]) for r in rows]


def main():
    parser = argparse.ArgumentParser(description="Plot concurrency -> latency chart from DB load test CSV")
    parser.add_argument("--csv", required=True, help="Input CSV path")
    parser.add_argument("--out", required=True, help="Output image path")
    args = parser.parse_args()

    rows = read_rows(args.csv)
    if not rows:
        raise SystemExit("CSV has no data rows")

    concurrency = to_int(rows, "concurrency")
    write_p95 = to_float(rows, "write_p95_ms")
    read_p95 = to_float(rows, "read_p95_ms")
    write_p99 = to_float(rows, "write_p99_ms")
    read_p99 = to_float(rows, "read_p99_ms")

    plt.figure(figsize=(10, 6))
    plt.plot(concurrency, write_p95, marker="o", label="write p95")
    plt.plot(concurrency, read_p95, marker="o", label="read p95")
    plt.plot(concurrency, write_p99, marker="x", linestyle="--", label="write p99")
    plt.plot(concurrency, read_p99, marker="x", linestyle="--", label="read p99")
    plt.xlabel("Concurrency")
    plt.ylabel("Latency (ms)")
    plt.title("DB Mixed Workload: Concurrency -> Latency")
    plt.grid(True, alpha=0.3)
    plt.legend()
    plt.tight_layout()
    plt.savefig(args.out, dpi=150)
    print(f"saved: {args.out}")


if __name__ == "__main__":
    main()

