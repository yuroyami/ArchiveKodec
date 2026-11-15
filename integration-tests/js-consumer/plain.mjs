// Plain JavaScript against the built npm module: no Kotlin, no types.
import * as ak from "./ArchiveKodec-KMP-archivekodec.mjs";
import assert from "node:assert/strict";
import { Buffer } from "node:buffer";

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

// WinZip AES: written with a password, read back, and refused without the right one.
const secret = ak.zip([new ak.ZipMember("notes/a.txt", asInt8), new ak.ZipMember("b.bin", big)], "pässword", 128);
const locked = ak.open(secret, null, "pässword");
assert.equal(locked.entries[0].isEncrypted, true);
assert.deepEqual(locked.read("notes/a.txt"), back);
assert.deepEqual(locked.readAt(1), big);
locked.close();
assert.throws(() => ak.open(secret, null, "wrong").read("b.bin"), /wrong password/);
assert.throws(() => ak.open(secret).read("b.bin"), /no password/);
assert.throws(() => ak.zip([new ak.ZipMember("a", hello)], "x", 64), /128, 192 or 256/);
assert.throws(() => ak.zip([new ak.ZipMember("a", hello)], ""), /must not be empty/);

// 7z AES with encrypted headers, written by 7-Zip 23.01 with the password SECRET.
const aesHeaders7z = Buffer.from(
    "N3q8ryccAASk5ycqAAUAAAAAAAAvAAAAAAAAAMe59eMgWdmaN+26dcnsIiEM5xDfD+2rktN5BijuOuLQUfG38CucrAgv6lY89W6m" +
    "/EEU2lNTqiKIypKeYisOIHOrpD5wItAgVFyRfqcaoS9P/Aihxpi9F9Pk+5s3MJRW6wP9XHFqUIDDMAjFWmqW1zoBZhZJcPCI92EC" +
    "KmgL+2FSfSlNVB/Z24Q6/6uenYz84TU4/0uoG3ij85XmRXwjjfvwk0cIFe0YWqysBW8TtueTgmL1OsNeniZiB6mweUs8XEEE4r6S" +
    "jR3iasOgIeY73uHMIRNz3BR5Zv8GnDuRcEq+2wUj8+pBF4f6BHwNQiew1rf+ELlsYKoUxoZ3oaR9JR9Qq5o9CLvjja8VZt3xhW7W" +
    "OmsQ3VyptCzSUp3lRXZTwlQZ3mrex9jQYJaZRYf+9i6S949rscmlvPJTA1Y9s3YkfhVBl4fbRIFf9JkJSfCUsm6CZplpwuJFlOcn" +
    "41ZiYxg8+swfuqV6oTMFbNi565lStK8nGD8zs8tkFipXdUJ1EXJ6YRWcNxpA3OViHVUboe0fuAXQDCC5hTpYchJStW2R4KhAQ5lA" +
    "4nZ/aqdbTqfXCi7VuW9sTG9O1Hr1q1hmAzIfQBVjKX62hCZX4/4m2TOy1lnrKDmIuKJ75Eq+zN+8DfWY1DfJhqNZkjIlT0u0oLx2" +
    "nEUaVn0+ZCqtvUeGMOk6doNBCxnxoTg18ybIslQjIcewYS9LuF+cefRq7LrIJc4FPUnZBNdaaDRvjxqkF/sKjyrVn4sG7U/w2/2g" +
    "1eQBi3B5zp/5IvAKFVOiuTN5Ga4F7WiESRCXTJSJXMckW2OE4WWqkKc4qcDXNVwM+1rTbDsvEwdFLo2+zoubA6qK/FywiGquxYVg" +
    "dUM6qB02fBkT7W9ZSDHlee8XA3f0it2sns6ok7QWlvHF8mhqfLXi1HCv/XdJsyS5tKCcKNGqHRrEXVx3ojTDJBKC+MDmVZf0c0gs" +
    "jZPyqvH8HRzLdZVa6i92lkJ3zss4qIUwIcdslDGz6M5b942eDQzdik7QXUI7aKoSECPtCa1lQdUswQyYY42HU2r82BBTj2O7cLoW" +
    "EGbLykPZT1eF1YzNnaWIP9rrcCa7wJgVps/k8TMCZ6+RL4iESPTNtSnAsfmXQqWtJge9+sP3jshYfNINoHuAOtdzdMi2sksRtuEp" +
    "ZKMgBfvygq332gyATb1pjNed+qjXF06kK3kOX5TCpIuZWE8qTy60Sw2qE0Ni5BNlUNCWGOjLzLn//jyz+n+2ZryPxRa1F5YKqoE3" +
    "vgBwfUsmAjon5gZDBQBy4c30uRDmg/xr5Gv5FMpCx3PSWgGKVWR+FM+iXaj2fedsSFz46rwy+mHXhnymSEzFoc3/ZKWZvRYPSPCZ" +
    "L825zrkuSGW0NtstHNl11emhQQb4BD4lyVbxzdzKFHuTzO5Bncfn4uGAkugRr3USpwEztMXpojMPpSMY1UF5Pj/w4hZ8pIANd9fM" +
    "uDv1iGTnuUvvE+6fpbgouXSvYlimRK782VsDq/8Q0uaVCG+hQsZfq7l3Bw8uK4Jklv2gSvwHSrGCi2SrDFOTgAA5GPRdCCntsQzm" +
    "kPn68wLT8H2L9dFnhrX1Khge44d4PuTBXuv4WIHRUE048j6vJSDkkeH2MVq5flfP8FRugdZMETCfTJ4afyy8J9Pu56ZY8wvB9pak" +
    "9scsYxT/lInwWgPaPd3mfYQWhVa44FDRmkhZePvM75Db02Hk3hcGhJABCXAABwsBAAEkBvEHARJTD4PodnMOE2RTZ0K9sGlO/hsM" +
    "agoBrhOwqgAA",
    "base64",
);
const sevenZip = ak.open(new Int8Array(aesHeaders7z.buffer, aesHeaders7z.byteOffset, aesHeaders7z.length), null, "SECRET");
assert.deepEqual(sevenZip.entries.map((e) => [e.name, e.size, e.isEncrypted]), [["in.txt", 3000, true]]);
assert.equal(sevenZip.read("in.txt").length, 3000);
sevenZip.close();
assert.throws(() => ak.open(new Int8Array(aesHeaders7z.buffer, aesHeaders7z.byteOffset, aesHeaders7z.length), null, "wrong"), /wrong password/);

console.log("plain.mjs: ok");
