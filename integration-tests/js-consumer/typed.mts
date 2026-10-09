// TypeScript against the generated declarations, compiled with --strict and then run.
import { Archive, Entry, ZipMember, gunzip, gzip, open, zip } from "./ArchiveKodec-KMP-archivekodec.mjs";

const hello: Int8Array = new Int8Array([104, 101, 108, 108, 111]);
const packed: Int8Array = gzip(hello, 9);
const unpacked: Int8Array = gunzip(packed, 1024);
if (unpacked.join() !== hello.join()) throw new Error("gzip round trip failed");

const archive: Archive = open(zip([new ZipMember("a.txt", hello)]), "zip");
const entries: Entry[] = archive.entries;
const size: number = entries[0].size;
const modified: number | null | undefined = entries[0].lastModified;
const link: string | null | undefined = entries[0].linkTarget;
if (size !== 5 || link != null || (modified != null && !(new Date(modified).getTime() > 0))) {
    throw new Error("unexpected entry metadata");
}
if (archive.read("a.txt").join() !== hello.join()) throw new Error("zip read failed");
archive.close();

// Compiled only, never called: each line must be a type error.
export function rejected(): void {
    // @ts-expect-error: bytes are an Int8Array, not a string.
    gzip("hello");
    // @ts-expect-error: entries are created by the library only.
    new Entry();
    // @ts-expect-error: sizes are numbers, not bigints or Kotlin Longs.
    const wide: bigint = archive.entries[0].size;
}

console.log("typed.mts: ok");
