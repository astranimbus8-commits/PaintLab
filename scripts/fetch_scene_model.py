"""Downloads the DeepLabV3 (MobileNetV2, ADE20K) TFLite model used for
sky / nature / water / building / people / vehicle selection and puts it in
app/src/main/assets/scene_model.tflite. Tries several sources; never fails
the build (the app still works without it, and a model can also be loaded
from inside the app via  ⋮ > Load scene model).
"""
import glob, io, os, shutil, sys, tarfile, urllib.request

DEST = os.path.join("app", "src", "main", "assets", "scene_model.tflite")
HANDLE = "spsayakpaul/deeplabv3-mobilenetv2/tfLite/ade20k"
URLS = [
    "https://www.kaggle.com/api/v1/models/spsayakpaul/deeplabv3-mobilenetv2/tfLite/ade20k/2/download",
    "https://www.kaggle.com/api/v1/models/spsayakpaul/deeplabv3-mobilenetv2/tfLite/ade20k/1/download",
    "https://tfhub.dev/sayakpaul/lite-model/deeplabv3-mobilenetv2-ade20k/1/default/2?lite-format=tflite",
]


def save(data: bytes) -> bool:
    # Either a raw .tflite (flatbuffer id "TFL3" at offset 4) or a tar.gz containing one.
    if len(data) > 8 and data[4:8] == b"TFL3":
        os.makedirs(os.path.dirname(DEST), exist_ok=True)
        open(DEST, "wb").write(data)
        return True
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as tar:
            for m in tar.getmembers():
                if m.name.endswith(".tflite"):
                    return save(tar.extractfile(m).read())
    except Exception as e:
        print("  not a tar archive:", e)
    return False


def via_kagglehub() -> bool:
    try:
        import kagglehub
        path = kagglehub.model_download(HANDLE)
        files = glob.glob(os.path.join(path, "**", "*.tflite"), recursive=True)
        if files:
            os.makedirs(os.path.dirname(DEST), exist_ok=True)
            shutil.copy(files[0], DEST)
            return True
    except Exception as e:
        print("  kagglehub failed:", e)
    return False


def via_url(url: str) -> bool:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=120) as r:
            return save(r.read())
    except Exception as e:
        print("  download failed:", e)
    return False


if __name__ == "__main__":
    print("Fetching scene model via kagglehub ...")
    ok = via_kagglehub()
    for u in URLS:
        if ok:
            break
        print("Trying", u)
        ok = via_url(u)
    if ok:
        print("Scene model saved:", DEST, os.path.getsize(DEST), "bytes")
    else:
        print("::warning::Scene model could not be downloaded. Sky/Nature/Buildings selection "
              "will ask you to load a model file in the app. Subject/Background still work.")
    sys.exit(0)
