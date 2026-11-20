"""Convert the Watanabe & Nakamura (OzCHI 2025) Extra Trees ONNX model to the app's binary format.

The model is not committed (license unspecified). Get `extra_trees.onnx` from the authors'
release (kntWT/posture-correction-backend, branch ozchi-2025), then run:

    pip install onnx
    python tools/convert_watanabe_model.py extra_trees.onnx \
        TTS/app/src/main/assets/neck/watanabe_extra_trees.bin

Output (little-endian), read by WatanabeNeckModel.kt:
    magic b"WNM1"
    float64[3] scaler mean, float64[3] scaler scale   (features: ratio, phone_tilt, face_pitch)
    int32 n_trees, int32 n_nodes
    int32[n_trees] root node index
    int8[n_nodes]    feature index (-1 = leaf)
    float32[n_nodes] threshold (branch: go "true" if x <= threshold) or leaf weight
    int32[n_nodes]   true child index, int32[n_nodes] false child index
Prediction = sum of the leaf weights reached in every tree.
"""
import struct
import sys

import onnx
from onnx import helper

# StandardScaler fitted by the authors (extra_trees_scaler.pkl), read from its raw float64 bytes.
SCALER_MEAN = (1.0837129401439556, 41.135937039823595, 8.040395774472866)
SCALER_SCALE = (0.35037281406847676, 22.580672229579946, 23.658343846657026)


def main(src, dst):
    node = onnx.load(src).graph.node
    assert len(node) == 1 and node[0].op_type == "TreeEnsembleRegressor"
    a = {x.name: helper.get_attribute_value(x) for x in node[0].attribute}
    assert a.get("post_transform", b"NONE") == b"NONE"
    assert a.get("aggregate_function", b"SUM") == b"SUM"
    assert not a.get("base_values")

    keys = list(zip(a["nodes_treeids"], a["nodes_nodeids"]))
    index = {k: i for i, k in enumerate(keys)}
    n = len(keys)
    feature = [-1] * n
    value = [0.0] * n
    true_idx = [-1] * n
    false_idx = [-1] * n
    for i, (t, _) in enumerate(keys):
        mode = a["nodes_modes"][i]
        if mode == b"LEAF":
            continue
        assert mode == b"BRANCH_LEQ" and a["nodes_missing_value_tracks_true"][i] == 0
        feature[i] = a["nodes_featureids"][i]
        value[i] = a["nodes_values"][i]
        true_idx[i] = index[(t, a["nodes_truenodeids"][i])]
        false_idx[i] = index[(t, a["nodes_falsenodeids"][i])]

    leaf_seen = set()
    for t, nid, w in zip(a["target_treeids"], a["target_nodeids"], a["target_weights"]):
        i = index[(t, nid)]
        assert feature[i] == -1 and i not in leaf_seen
        leaf_seen.add(i)
        value[i] = w
    assert len(leaf_seen) == feature.count(-1)

    trees = sorted(set(a["nodes_treeids"]))
    roots = [index[(t, 0)] for t in trees]

    with open(dst, "wb") as f:
        f.write(b"WNM1")
        f.write(struct.pack("<3d", *SCALER_MEAN))
        f.write(struct.pack("<3d", *SCALER_SCALE))
        f.write(struct.pack("<2i", len(roots), n))
        f.write(struct.pack(f"<{len(roots)}i", *roots))
        f.write(struct.pack(f"<{n}b", *feature))
        f.write(struct.pack(f"<{n}f", *value))
        f.write(struct.pack(f"<{n}i", *true_idx))
        f.write(struct.pack(f"<{n}i", *false_idx))
    print(f"{len(roots)} trees, {n} nodes -> {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
