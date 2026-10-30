from __future__ import annotations

import argparse
import json
from pathlib import Path

from .data import (
    DatasetError,
    convert_inventory,
    inventory_dataset,
    load_inventory_manifest,
    require_image_dependencies,
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Inventory and convert Surface_1 and Cityscapes into portrait training data."
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--inventory-only",
        action="store_true",
        help="Write the grouped manifest and source audit without decoding images.",
    )
    mode.add_argument(
        "--convert",
        action="store_true",
        help="Inventory the data and write paired portrait tensors, labels, and masks.",
    )
    parser.add_argument(
        "--dataset-root",
        type=Path,
        help="Directory containing Surface_1 and cityscapes.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="Output directory, normally build/vision-training/data.",
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--inventory-manifest",
        type=Path,
        help="Reuse an existing manifest.json and sibling inventory_report.json for bounded conversion.",
    )
    parser.add_argument(
        "--sample-per-source",
        type=int,
        help=(
            "Convert at most this many paired records per source for a bounded verification run; "
            "Surface selection prefers XML frames covering hazard classes."
        ),
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.sample_per_source is not None and not args.convert:
        parser.error("--sample-per-source is only valid with --convert.")
    if args.sample_per_source is not None and args.sample_per_source < 1:
        parser.error("--sample-per-source must be positive.")
    if args.inventory_manifest and not args.convert:
        parser.error("--inventory-manifest is only valid with --convert.")
    if args.inventory_manifest and args.dataset_root:
        parser.error("Use either --inventory-manifest or --dataset-root, not both.")
    if not args.inventory_manifest and not args.dataset_root:
        parser.error("--dataset-root is required unless --inventory-manifest is provided.")
    if args.convert:
        try:
            require_image_dependencies()
        except RuntimeError as exc:
            parser.error(str(exc))

    try:
        if args.inventory_manifest:
            inventory_manifest = args.inventory_manifest.expanduser().resolve()
            if inventory_manifest.parent != args.output.expanduser().resolve():
                parser.error("--inventory-manifest must be inside the --output directory.")
            inventory = load_inventory_manifest(inventory_manifest)
        else:
            inventory = inventory_dataset(args.dataset_root, args.output, args.seed)
        if args.convert:
            convert_inventory(inventory, args.output, args.seed, args.sample_per_source)
    except (DatasetError, OSError, RuntimeError) as exc:
        parser.error(str(exc))

    report_path = args.output / ("completion_report.json" if args.convert else "inventory_report.json")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    print(json.dumps(report, indent=2, ensure_ascii=False))
    print(f"Manifest: {args.output / 'manifest.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
