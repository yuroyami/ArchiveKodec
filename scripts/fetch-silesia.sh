#!/bin/sh
# Download the Silesia Corpus (12 files, 211,938,580 bytes) for the opt-in corpus test:
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
total=$(cat "$dir"/dickens "$dir"/mozilla "$dir"/mr "$dir"/nci "$dir"/ooffice "$dir"/osdb "$dir"/reymont \
  "$dir"/samba "$dir"/sao "$dir"/webster "$dir"/x-ray "$dir"/xml | wc -c)
[ "$total" -eq 211938580 ] || { echo "unexpected Silesia size $total" >&2; exit 1; }
echo "Silesia Corpus ready in $dir"
