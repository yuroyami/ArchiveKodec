# The Canterbury Corpus

The eleven files of the standard Canterbury Corpus (University of Canterbury,
https://corpus.canterbury.ac.nz), 2,810,784 bytes in all, as mirrored at
https://github.com/pfalcon/canterbury-corpus. They are the usual test set for lossless
compressors: English text, Shakespeare, HTML, C and Lisp source, a spreadsheet, a fax
image, a SPARC executable and a man page. `SHA256SUMS` pins their contents.

`CorpusRoundTripTest` round-trips every file through every encoder and decodes the
DEFLATE family with `java.util.zip` as the reference.
