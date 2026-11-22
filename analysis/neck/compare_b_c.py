"""Mode B / mode C comparison and face-angle validation (design doc §11.7).

Inputs are the CSV files the collection screen writes to neck_logs/ (adb pull):
  frames_<session>.csv      one row per camera frame (mode B and mode C columns)
  calib_c_<name>.csv        one row per mode C calibration step

Usage:
  python analysis/neck/compare_b_c.py compare frames_a.csv [frames_b.csv ...]
      Availability of each estimate and agreement between mode B (neck angle), mode C (head flexion θ_C)
      and the baseline θ_phone − face_pitch (ML Kit). There is no ground truth: this is agreement, not accuracy.

  python analysis/neck/compare_b_c.py sweep --calib calib_c_X.csv frames_sweep.csv [...]
      Head fixed against a wall, phone moved up/down at 25/35/45 cm while facing the face.
      Ground-truth face pitch = phone tilt. Compares
        B: c_pitch_deg (per-user 3D model + PnP, in the app)
        A: nose position ratio interpolated between the calibration steps (offline)
        D: ML Kit face_pitch with a per-user linear correction fitted on the calibration steps (offline)
"""
import argparse
import sys

import numpy as np
import pandas as pd

BENT_DEG = 15.0
CENTER_TOLERANCE = 0.15
PITCH_STEPS = ["FRONT", "UP_22", "UP_45", "DOWN_22", "DOWN_45"]


def load_frames(paths):
    frames = [pd.read_csv(p).assign(session=p) for p in paths]
    return pd.concat(frames, ignore_index=True)


def agreement(name, a, b):
    """Agreement of two estimate series on the frames where both exist."""
    both = a.notna() & b.notna()
    n = int(both.sum())
    if n < 2:
        print(f"  {name}: {n} frames with both values")
        return
    x, y = a[both], b[both]
    d = x - y
    r = np.corrcoef(x, y)[0, 1]
    same = ((x >= BENT_DEG) == (y >= BENT_DEG)).mean()
    print(
        f"  {name}: n={n}  r={r:.3f}  mean diff={d.mean():+.2f}°  MAE={d.abs().mean():.2f}°  "
        f"95% LoA=[{d.mean() - 1.96 * d.std():+.1f}, {d.mean() + 1.96 * d.std():+.1f}]°  "
        f"bent(≥{BENT_DEG:.0f}°) agreement={same * 100:.1f}%"
    )


def cmd_compare(args):
    df = load_frames(args.frames)
    b = df["neck_pred_deg"]
    c = df["neck_c_deg"]
    base = df["phone_tilt_deg"] - df["face_pitch"]
    n = len(df)
    print(f"frames: {n} ({df['session'].nunique()} sessions)")
    print("availability:")
    for name, s in [("face detected", df["face_pitch"]), ("mode B", b), ("mode C", c), ("baseline", base)]:
        print(f"  {name:13s} {s.notna().sum():6d}  {s.notna().mean() * 100:5.1f}%")
    only_c = (c.notna() & b.isna()).sum()
    print(f"  mode C without mode B: {only_c} ({only_c / n * 100:.1f}%)")
    print("agreement (first − second):")
    agreement("C − B", c, b)
    agreement("baseline − B", base, b)
    agreement("C − baseline", c, base)


def eye_frame_ratio(eye_l, eye_r, nose, mouth_l, mouth_r):
    """Nose base position between the eye line (0) and the mouth line (1), measured along the face's down axis."""
    e = (eye_l + eye_r) / 2
    ex = eye_l - eye_r
    ex = ex / np.linalg.norm(ex, axis=-1, keepdims=True)
    ey = np.stack([-ex[..., 1], ex[..., 0]], axis=-1)
    m = (mouth_l + mouth_r) / 2
    return ((nose - e) * ey).sum(-1) / ((m - e) * ey).sum(-1)


def xy(df, prefix):
    return df[[f"{prefix}_x", f"{prefix}_y"]].to_numpy(dtype=float)


def interp_extrapolate(x, xp, fp):
    """Piecewise-linear interpolation, linearly extrapolated with the end segments."""
    order = np.argsort(xp)
    xp, fp = np.asarray(xp)[order], np.asarray(fp)[order]
    y = np.interp(x, xp, fp)
    lo, hi = x < xp[0], x > xp[-1]
    y[lo] = fp[0] + (x[lo] - xp[0]) * (fp[1] - fp[0]) / (xp[1] - xp[0])
    y[hi] = fp[-1] + (x[hi] - xp[-1]) * (fp[-1] - fp[-2]) / (xp[-1] - xp[-2])
    return y


def errors(name, est, truth, dist):
    ok = est.notna() & truth.notna()
    if ok.sum() == 0:
        print(f"  {name}: no frames")
        return
    e = est[ok] - truth[ok]
    print(f"  {name}: n={int(ok.sum())}  MAE={e.abs().mean():.2f}°  RMSE={np.sqrt((e ** 2).mean()):.2f}°  bias={e.mean():+.2f}°")
    bins = pd.cut(dist[ok], [0, 30, 40, np.inf], labels=["<30 cm", "30–40 cm", "≥40 cm"])
    for label, g in e.groupby(bins, observed=True):
        print(f"      {label:9s} n={len(g):5d}  MAE={g.abs().mean():.2f}°")
    unknown = bins.isna().sum()
    if unknown:
        print(f"      no distance n={unknown}  MAE={e[bins.isna()].abs().mean():.2f}°")


def cmd_sweep(args):
    calib = pd.read_csv(args.calib).set_index("step")
    df = load_frames(args.frames)
    centered = (
        ((df["face_cx"] - df["image_w"] / 2).abs() <= CENTER_TOLERANCE * df["image_w"])
        & ((df["face_cy"] - df["image_h"] / 2).abs() <= CENTER_TOLERANCE * df["image_h"])
    )
    df = df[centered & df["phone_tilt_deg"].notna()].copy()
    truth = df["phone_tilt_deg"]
    dist = df["c_dist_cm"]
    print(f"centered frames with phone tilt: {len(df)}")

    steps = calib.loc[[s for s in PITCH_STEPS if s in calib.index]]

    # A: nose ratio, interpolated between the pitch calibration steps.
    p_cal = eye_frame_ratio(xy(steps, "eye_l"), xy(steps, "eye_r"), xy(steps, "nose_base"), xy(steps, "mouth_l"), xy(steps, "mouth_r"))
    order = np.argsort(steps["label_pitch_deg"].to_numpy())
    if not (np.all(np.diff(p_cal[order]) > 0) or np.all(np.diff(p_cal[order]) < 0)):
        print("  warning: nose ratio is not monotonic over the calibration steps", file=sys.stderr)
    p = eye_frame_ratio(xy(df, "eye_l"), xy(df, "eye_r"), xy(df, "nose"), xy(df, "mouth_l"), xy(df, "mouth_r"))
    a_est = pd.Series(np.nan, index=df.index)
    valid = np.isfinite(p)
    a_est[valid] = interp_extrapolate(p[valid], p_cal, steps["label_pitch_deg"].to_numpy())

    # D: ML Kit pitch with a per-user linear correction.
    slope, intercept = np.polyfit(steps["face_pitch_deg"], steps["label_pitch_deg"], 1)
    d_est = slope * df["face_pitch"] + intercept
    print(f"  D correction: label ≈ {slope:.3f}·face_pitch {intercept:+.2f}")

    print("face pitch error (estimate − phone tilt), each method on its own frames:")
    for name, est in [("B 3D model+PnP", df["c_pitch_deg"]), ("A ratio interp", a_est), ("D ML Kit+linear", d_est)]:
        errors(name, est, truth, dist)
    common = df["c_pitch_deg"].notna() & a_est.notna() & d_est.notna()
    print(f"same frames (all three available, n={int(common.sum())}):")
    for name, est in [("B 3D model+PnP", df["c_pitch_deg"]), ("A ratio interp", a_est), ("D ML Kit+linear", d_est)]:
        errors(name, est.where(common), truth, dist)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_cmp = sub.add_parser("compare")
    p_cmp.add_argument("frames", nargs="+")
    p_cmp.set_defaults(func=cmd_compare)
    p_sw = sub.add_parser("sweep")
    p_sw.add_argument("--calib", required=True)
    p_sw.add_argument("frames", nargs="+")
    p_sw.set_defaults(func=cmd_sweep)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
