#!/bin/bash -ex
# used from ci.jenkins-ci.org to actually generate the production OSS update center

umask

# prepare the www workspace for execution
rm -rf www2 || true
mkdir www2

mvn -e clean install

function generate() {
    java -jar target/update-center2-*-bin*/update-center2-*.jar \
      -id default \
      -connectionCheckUrl http://www.google.com/ \
      -key $SECRET/update-center.key \
      -certificate $SECRET/update-center.cert \
      "$@"
}

function sanity-check() {
    dir="$1"
    file="$dir/update-center.json"
    if [ 800000 -ge $(wc -c "$file" | cut -f 1 -d ' ') ]; then
        echo $file looks too small
        exit 1
    fi
}

RULE="$PWD/www2/rules.php"
echo '<?php $rules = array( ' > "$RULE"

# generate several update centers for different segments
# so that plugins can aggressively update baseline requirements
# without strnding earlier users.
#
# we use LTS as a boundary of different segments, to create
# a reasonable number of segments with reasonable sizes. Plugins
# tend to pick LTS baseline as the required version, so this works well.
#
# Looking at statistics like http://stats.jenkins-ci.org/jenkins-stats/svg/201409-jenkins.svg,
# I think three or four should be sufficient

# make sure the latest baseline version here is available as LTS and in the Maven index of the repo, 
# otherwise it'll offer the weekly as update to a running LTS version
declare -a baselines=( 1.554 1.565 1.580 1.596 1.609 1.625 1.642 1.651 )

for v in ${baselines[@]}; do
    # for mainline up to $v, which advertises the latest core
    generate -no-experimental -skip-release-history -www ./www2/$v -cap $v.999 -capCore 1.999
    sanity-check ./www2/$v

    # for LTS
    generate -no-experimental -skip-release-history -www ./www2/stable-$v -cap $v.999 -capCore ${baselines[${#baselines[@]}-1]}.999
    sanity-check ./www2/stable-$v
    lastLTS=$v

    echo "'$v' => '$v', " >> "$RULE"
    echo "'$v.999' => 'stable-$v', " >> "$RULE"
done

# On generating http://mirrors.jenkins-ci.org/plugins layout
#     this directory that hosts actual bits need to be generated by combining both experimental content and current content,
#     with symlinks pointing to the 'latest' current versions. So we generate exprimental first, then overwrite current to produce proper symlinks

# experimental update center. this is not a part of the version-based redirection rules
generate -skip-release-history -www ./www2/experimental -download ./download

# for the latest without any cap
# also use this to generae https://updates.jenkins-ci.org/download layout, since this generator run
# will capture every plugin and every core
generate -no-experimental -www ./www2/current -www-download ./www2/download -download ./download -pluginCount.txt ./www2/pluginCount.txt

echo "); ?>" >> "$RULE"

# generate symlinks to retain compatibility with past layout and make Apache index useful
pushd www2
  ln -s stable-$lastLTS stable
  for f in latest latestCore.txt release-history.json update-center.json update-center.json.html; do
    ln -s current/$f .
  done

  # copy other static resource files
  rsync -avz "../site/static/" ./
popd

# push plugins to mirrors.jenkins-ci.org
chmod -R a+r download
rsync -avz download/plugins/ www-data@localhost:/srv/releases/jenkins/plugins

# push generated index to the production servers
# 'updates' come from tool installer generator, so leave that alone, but otherwise
# delete old sites
chmod -R a+r www2
rsync -avz www2/ --exclude=/updates --delete www-data@updates.jenkins-ci.org:/var/www/updates2.jenkins-ci.org/
