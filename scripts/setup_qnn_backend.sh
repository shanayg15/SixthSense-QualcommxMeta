#!/usr/bin/env bash
# Fetch the prebuilt Qualcomm QNN (Hexagon NPU) ExecuTorch Android AAR so the app
# can be built with `-PuseQnn=true`. Run from the repo root or anywhere.
#
# This downloads ONLY the QNN-enabled executorch runtime AAR. To actually execute
# on the Hexagon NPU you STILL need two things this script cannot fetch:
#   1) The Qualcomm QNN SDK HTP runtime .so libs for SM8750 / Hexagon v79
#      (libQnnHtp.so, libQnnHtpV*Stub.so + the matching HTP skel, libQnnSystem.so).
#      Copy them to android/app/src/main/jniLibs/arm64-v8a/ from your QNN SDK.
#   2) A QNN-exported .pte (QnnPartitioner + HTP compiler spec for SM8750), built
#      on the Linux export box, dropped into android/app/src/main/assets/models/.
# With those in place: ./gradlew assembleDebug -PuseQnn=true
set -euo pipefail

ET_VERSION="1.3.1"
AAR_URL="https://ossci-android.s3.amazonaws.com/executorch/release/${ET_VERSION}-qnn/executorch.aar"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS_DIR="${REPO_ROOT}/android/app/libs"
DEST="${LIBS_DIR}/executorch-qnn-${ET_VERSION}.aar"

mkdir -p "${LIBS_DIR}"
echo "Downloading QNN ExecuTorch AAR ${ET_VERSION} ->"
echo "  ${DEST}"
curl -fL --progress-bar "${AAR_URL}" -o "${DEST}"

echo
echo "Done: $(du -h "${DEST}" | cut -f1) at ${DEST}"
echo
echo "Next (NOT automated — need the Qualcomm QNN SDK / Linux export box):"
echo "  1) Copy QNN HTP runtime .so for SM8750/Hexagon-v79 into:"
echo "       android/app/src/main/jniLibs/arm64-v8a/"
echo "       (libQnnHtp.so, libQnnHtpV*Stub.so + HTP skel, libQnnSystem.so, stubs)"
echo "  2) Drop a QNN-exported depth.pte / yolo.pte into:"
echo "       android/app/src/main/assets/models/"
echo "  3) Build on the NPU backend:"
echo "       (cd android && ./gradlew assembleDebug -PuseQnn=true)"
echo
echo "The default CPU build (no flag) keeps working with XNNPACK .pte files."
