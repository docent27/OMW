#!/bin/bash

set -e
mkdir -p build
cd build

# Build a version of ICU for the host so that it can use the tools during the cross-compilation
mkdir -p icu-host-build
cd icu-host-build
if [ -r ../extern/fetched/icu/icu4c/source/configure ]; then
    ICU_SOURCE_DIR=../extern/fetched/icu/icu4c/source
else
    wget https://github.com/unicode-org/icu/archive/refs/tags/release-70-1.zip
    unzip release-70-1.zip
    ICU_SOURCE_DIR=./icu-release-70-1/icu4c/source
fi
${ICU_SOURCE_DIR}/configure --disable-tests --disable-samples --disable-icuio --disable-extras CC="gcc" CXX="g++"
make -j $(nproc)
cd ../..
