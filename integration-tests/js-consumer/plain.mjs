// Plain JavaScript against the built npm module: no Kotlin, no types.
import * as ak from "./ArchiveKodec-KMP-archivekodec.mjs";
import assert from "node:assert/strict";

const hello = new Int8Array([104, 101, 108, 108, 111]);
assert.deepEqual(ak.gunzip(ak.gzip(hello)), hello);
assert.deepEqual(ak.inflate(ak.deflate(hello, 9)), hello);
assert.deepEqual(ak.unzlib(ak.zlib(hello, 1)), hello);
assert.deepEqual(ak.unlz4(ak.lz4(hello)), hello);
assert.deepEqual(ak.decompressAny(ak.compress("gzip", hello)), hello);
assert.deepEqual(ak.decompress("zlib", ak.compress("zlib", hello)), hello);
assert.equal(ak.sniff(ak.gzip(hello)), "gzip");
assert.ok(ak.codecs().includes("zstd"));
assert.match(ak.version(), /^\d+\.\d+/);

// A Uint8Array goes in as a view, and comes back out the same way.
const text = new TextEncoder().encode("héllo, archive");
const asInt8 = new Int8Array(text.buffer, text.byteOffset, text.length);
const big = new Int8Array(200_000).fill(7);
const zipped = ak.zip([new ak.ZipMember("notes/a.txt", asInt8), new ak.ZipMember("b.bin", big)]);
assert.equal(ak.sniff(zipped), "zip");

const archive = ak.open(zipped);
assert.equal(archive.format, "zip");
const entries = archive.entries;
assert.deepEqual(entries.map((e) => e.name), ["notes/a.txt", "b.bin"]);
assert.equal(entries[1].size, 200_000);
assert.equal(typeof entries[1].compressedSize, "number");
assert.ok(entries[1].compressedSize < 200_000);
assert.equal(entries[0].isDirectory, false);
assert.equal(entries[0].codec, "deflate");
assert.ok(entries[0].lastModified === null || typeof entries[0].lastModified === "number");
const back = archive.read("notes/a.txt");
assert.equal(new TextDecoder().decode(new Uint8Array(back.buffer, back.byteOffset, back.length)), "héllo, archive");
assert.deepEqual(archive.readAt(1), big);

// Failures are Errors with a message.
assert.throws(() => archive.read("missing"), (e) => e instanceof Error && /no member named 'missing'/.test(e.message));
assert.throws(() => archive.readAt(5), Error);
assert.throws(() => ak.gunzip(new Int8Array([1, 2, 3])), Error);
assert.throws(() => ak.compress("nope", hello), /no codec named 'nope'/);
assert.throws(() => ak.gunzip(ak.gzip(big), 1000), Error);
assert.throws(() => ak.open(zipped, null, null, -1), /maxBytes/);
assert.throws(() => ak.open(new Int8Array(64)), Error);
assert.deepEqual(ak.open(zipped, "zip", null, 1000).read("notes/a.txt"), back);
assert.throws(() => ak.open(zipped, null, null, 1000).readAt(1), Error);
archive.close();
assert.throws(() => archive.read("notes/a.txt"), Error);

console.log("plain.mjs: ok");
