#!/usr/bin/env python3
"""Generate pinned Xray assets; only Python stdlib and a temporary Go module required."""
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SOURCES = json.loads((HERE / "sources.json").read_text())


def download(url, expected=None):
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    actual = hashlib.sha256(data).hexdigest()
    if expected is not None and actual != expected:
        raise ValueError(f"SHA-256 mismatch for {url}: {actual}, expected {expected}")
    return data


def main():
    destination = ROOT / "app/src/main/assets"
    with tempfile.TemporaryDirectory(prefix="luxynet-geodata-") as temporary:
        work = Path(temporary)
        licenses = {}
        for key in ("geoip", "geosite", "protobuf"):
            source = SOURCES[key]
            data = download(source["url"], source["sha256"])
            if key != "protobuf":
                # Independent upstream published checksum, also matched against GitHub's
                # release API asset digest when these pins were selected.
                checksum = download(source["checksum_url"]).decode().split()[0]
                if checksum != source["sha256"]:
                    raise ValueError(f"Upstream checksum changed for {key}")
                (work / f"{key}.dat").write_bytes(data)
            else:
                (work / "official").mkdir()
                (work / "official/geodat.pb.go").write_bytes(data)
            licenses[key] = download(source["license_url"], source["license_sha256"])
        (work / "XRAY-LICENSE").write_bytes(licenses["protobuf"])
        shutil.copyfile(HERE / "filter.go", work / "main.go")
        (work / "go.mod").write_text(
            "module geodata-generator\n\ngo 1.27.0\n\n"
            "require google.golang.org/protobuf v1.36.12\n"
        )
        (work / "go.sum").write_text(
            "google.golang.org/protobuf v1.36.12 h1:pJOKDDOyeXErUroCihFAd5LQuwXBSpVnKGrj5o/fwxc=\n"
            "google.golang.org/protobuf v1.36.12/go.mod h1:HTf+CrKn2C3g5S8VImy6tdcUvCska2kB7j23XfzDpco=\n"
        )
        environment = os.environ.copy()
        environment["GOTOOLCHAIN"] = "go1.27.0"
        subprocess.run(
            ["go", "run", "-mod=readonly", ".", str(work / "geoip.dat"),
             str(work / "geosite.dat"), str(work / "output")],
            cwd=work, env=environment, check=True,
        )
        manifest = dict(SOURCES)
        manifest["generated"] = json.loads((work / "output/counts.json").read_text())
        manifest["assets"] = {}
        for name in ("geoip.dat", "geosite.dat"):
            data = (work / "output" / name).read_bytes()
            manifest["assets"][name] = {
                "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                "gzip_level9_bytes": len(gzip.compress(data, compresslevel=9, mtime=0)),
            }
        destination.mkdir(parents=True, exist_ok=True)
        license_dir = destination / "geodata-licenses"
        license_dir.mkdir(exist_ok=True)
        for key in ("geoip", "geosite"):
            (license_dir / f"{key}-LICENSE.txt").write_bytes(licenses[key])
        for name in ("geoip.dat", "geosite.dat"):
            shutil.copyfile(work / "output" / name, destination / name)
        (destination / "geodata-sources.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n"
        )
        (destination / "routing-assets.properties").write_text(
            "".join(
                f"{kind}.sha256={manifest['assets'][kind + '.dat']['sha256']}\n"
                f"{kind}.size={manifest['assets'][kind + '.dat']['bytes']}\n"
                for kind in ("geoip", "geosite")
            )
        )
        print(json.dumps({"assets": manifest["assets"], "unsupported_countries":
                          manifest["generated"]["unsupported_iso3166_1_alpha2"]}, indent=2))


if __name__ == "__main__":
    main()
