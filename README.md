ScrubSum: a shasum-compatible no-frills data scrubber
=====================================================

ScrubSum is an open source project to check data integrity of long-term archival
file storage

You have backups already (or at least you should). But backups aren't backups
unless you verify them. ScrubSum verifies your archives and backups by reading
them byte-for-byte from disk and re-computing their SHA1 checksum, and comparing
it to known-good checksums. ScrubSum will report new files, modified files, and
deleted files, and let you review them before committing them to the known-good
checksums file. All of the checksums are stored in a SCRUBSUMS file located in
the root of the directory you are checksumming.

## Usage examples

Download ScrubSum to your computer and run the `./scrubsum` helper script that
is located in the root of the project directory. I recommend that you add the
project directory to your PATH, so you can use the `scrubsum` command from
anywhere.

```shell
$ scrubsum
Usage: scrubsum [--verify-only] [--commit-changes] target_directory
$ scrubsum ~/Archives/
scrub: 10 files
scrub: 20 files
scrub: 30 files
scrub: 40 files
scrub: 50 files
scrub: 60 files
scrub: 70 files
scrub: 80 files
scrub: 90 files
scrub: 100 files
scrub: 200 files
scrub: 300 files
scrub: 400 files
scrub: 500 files
scrub: 600 files
scrub: 700 files
scrub: 800 files
scrub: 900 files
scrub: 1000 files
scrub: 1941 files
No changes detected.
```

## Requirements

* JDK 6+
* POSIX-compliant OS

You should be able to get ScrubSum working in Windows with a little hacking, but
it is not officially supported.

## Performance tuning

On magnetic disks (especially high-latency USB external drives), there are high
costs to reading large numbers of small files. This is because magnetic disks
typically have high random-access latencies compared to the bandwidth they offer
for sequential reads.

ScrubSum utilizes a large number of threads for IO operations, to try to keep
the disk request queue relatively busy. This is so that the disk controller can
better optimize the order in which it services requests. You can improve the
performance of ScrubSum further by compacting infrequently-accessed data into a
single compressed archive, which is treated as a single file on the file system.
I recommend the widely-compatible .zip format instead of typical .tar archives,
because .zip files support constant-time random access operations to individual
files. With this scheme, I can max out the sequential read bandwidth of my
external hard drive with ScrubSum.

You can also adjust the number of threads that ScrubSum uses internally, but the
default value seems to work best under my unscientific testing. If you are
performing data scrubbing on a Solid State Drive, you shouldn't need to worry
about these performance details too much.

## Compatibility with shasum

The popular shasum utility (included in Ubuntu and OS X) is compatible with
ScrubSum's checksum file. The checksum file is plaintext and can be integrated
with version control, text processing utilities, etc.

To check ScrubSum's checksum file with shasum, run:

    shasum -c SCRUBSUM

