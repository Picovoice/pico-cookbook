#
# Copyright 2025 Picovoice Inc.
#
# You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
# file accompanying this source.
#
# Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
# an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.
#

import os
import shutil
import struct

from argparse import ArgumentParser


if __name__ == "__main__":
    parser = ArgumentParser()
    parser.add_argument(
        "--porcupine_repo",
        "-p",
        required=True,
        help="Path to the root of the Porcupine repository",
    )
    parser.add_argument(
        "--rhino_repo",
        "-r",
        required=True,
        help="Path to the root of the Rhino repository",
    )
    args = parser.parse_args()

    ppn_includes = [
        "picovoice.h",
        "pv_porcupine_mcu.h"
    ]
    rhn_includes = [
        "pv_rhino_mcu.h"
    ]
    include_folders = [
        "stm32f411/stm32f411e-disco/Inc/",
    ]

    lib_languages = {
        "de",
        "en",
        "es",
        "fr",
        "it",
        "ja",
        "ko",
        "pt",
        "zh",
    }
    ppn_libs = [
        "libpv_porcupine.a"
    ]
    rhn_libs = [
        "libpv_rhino.a"
    ]
    lib_folders = {
        "stm32f411": "stm32f411/stm32f411e-disco/Lib/",
    }

    for include_folder in include_folders:
        for ppn_include in ppn_includes:
            src_filepath = os.path.join(args.porcupine_repo, "include", ppn_include)
            shutil.copy(src_filepath, include_folder)
        for rhn_include in rhn_includes:
            src_filepath = os.path.join(args.rhino_repo, "include", rhn_include)
            shutil.copy(src_filepath, include_folder)

    for platform, lib_folder in lib_folders.items():
        for language in lib_languages:
            for ppn_lib in ppn_libs:
                src_filepath = os.path.join(args.porcupine_repo, "lib", "mcu", platform, language, ppn_lib)
                dst_dirpath = os.path.join(lib_folder, language)
                os.makedirs(dst_dirpath, exist_ok=True)
                shutil.copy(src_filepath, dst_dirpath)
            for rhn_lib in rhn_libs:
                src_filepath = os.path.join(args.rhino_repo, "lib", "mcu", platform, language, rhn_lib)
                dst_dirpath = os.path.join(lib_folder, language)
                os.makedirs(dst_dirpath, exist_ok=True)
                shutil.copy(src_filepath, dst_dirpath)