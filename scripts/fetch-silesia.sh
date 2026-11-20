#!/bin/sh
# Download the Silesia Corpus (12 files, 211,938,580 bytes) and check each file's SHA-256, for the
# opt-in corpus test and scripts/compression-ratios.py:
#   scripts/fetch-silesia.sh /path/to/silesia
#   ARCHIVEKODEC_SILESIA=/path/to/silesia ./kotlin test -m archivekodec -p jvm --include-classes='*CorpusRoundTripTest'
# The files come from the GitHub mirror of the corpus (one zip per file); the original is
# at https://sun.aei.polsl.pl//~sdeor/index.php?page=silesia.
set -eu
dir="${1:?usage: fetch-silesia.sh DIRECTORY}"
mkdir -p "$dir"
for name in dickens mozilla mr nci ooffice osdb reymont samba sao webster x-ray xml; do
  curl -fsSL -o "$dir/$name.zip" "https://raw.githubusercontent.com/MiloszKrajewski/SilesiaCorpus/master/$name.zip"
  unzip -o -q "$dir/$name.zip" -d "$dir"
  rm "$dir/$name.zip"
done
# SHA-256 of each file, as scripts/compression-ratios.py also checks them.
sums='b24c37886142e11d0ee687db6ab06f936207aa7f2ea1fd1d9a36763c7a507e6a  dickens
657fc3764b0c75ac9de9623125705831ebbfbe08fed248df73bc2dc66e2a963b  mozilla
68637ed52e3e4860174ed2dc0840ac77d5f1a60abbcb13770d5754e3774d53e6  mr
fc63a31770947b8c2062d3b19ca94c00485a232bb91b502021948fee983e1635  nci
e7ee013880d34dd5208283d0d3d91b07f442e067454276095ded14f322a656eb  ooffice
60f027179302ca3ad87c58ac90b6be72ec23588aaa7a3b7fe8ecc0f11def3fa3  osdb
0eac0114a3dfe6e2ee1f345a0f79d653cb26c3bc9f0ed79238af4933422b7578  reymont
93ba07bc44d8267789c1d911992f40b089ffa2140b4a160fac11ccae9a40e7b2  samba
c2d0ea2cc59d4c21b7fe43a71499342a00cbe530a1d5548770e91ecd6214adcc  sao
6a68f69b26daf09f9dd84f7470368553194a0b294fcfa80f1604efb11143a383  webster
7de9fce1405dc44ae5e6813ed21cd5751e761bd4265655a005d39b9685d1c9ad  x-ray
0e82e54e695c1938e4193448022543845b33020c8be6bf3bf3ead2224903e08c  xml'
if command -v sha256sum >/dev/null; then check="sha256sum -c --quiet"; else check="shasum -a 256 -c --quiet"; fi
echo "$sums" | (cd "$dir" && $check) || { echo "a Silesia file has an unexpected SHA-256" >&2; exit 1; }
echo "Silesia Corpus ready in $dir"
