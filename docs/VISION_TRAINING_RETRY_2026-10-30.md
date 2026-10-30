# 2026-10-30 fresh-session retry contract

This supplements the existing four Orca Tasks; it does not create duplicate ownership. The user explicitly requested closing the unresponsive sessions and starting fresh sessions for unanswered work. All four prior Dispatches were fenced with worker-abandon and their exact terminals were closed with ptyKilled=true. Partial files and generated data were preserved. No provider quota error has yet been proven; missing replies are not quota evidence.

Read AGENTS.md's 2026-10-30 adaptive-model override before older pinned-model clauses. Use only your live injected lifecycle IDs and handle, send the prescribed heartbeat, check/ack mail at natural checkpoints and before worker_done, and send worker_done exactly once with true outcome. Do not commit, push, reboot, install WSL, run Gradle, use a device, replace app assets, or start full training. Coordinator owns independent combined validation; workers may run their narrow Python validation. Raw datasets remain immutable.

## Trainer task task_8bf6c98a3dbf

Exclusive files remain train.py, tests/test_train.py, tests/test_train_stage.py, TRAINING.md under tools/vision/training. Stronger model selected: Antigravity Claude Sonnet 5.5 High (claude-sonnet-5-5-high), discovered via agy models. Existing test_train.py 7 tests pass. Independent test_train_stage.py has 6 cases and 2 errors: train.py:1742 references batches.yielded_samples, but _iter_limited_batches returns a generator. Use the trainer's actual sample counter or a valid wrapper for empty-epoch detection; preserve no-sample rejection. Run the actual stage regression suite after correction, inspect other integration failures, fix in scope, and document remaining unsupported C resume honestly. Same-stage B resume and cold Keras3 optimizer restore regressions already exist and must keep passing.

Validation: build/vision-training/environment/venv_win_py311/Scripts/python.exe -m unittest discover -s tools/vision/training/tests -p test_train_stage.py -v and test_train.py. Do not use py launcher or another interpreter lacking TensorFlow. Do not edit loader/model/export/loss files.

## WSL paths task task_4e2e0fd8e1a5

Exclusive files: tools/vision/training/train_data.py, TRAIN_DATA_CONTRACT.md, tests/test_train_data.py. Moderate model selected: Antigravity Gemini 3.8 Flash Medium. Existing source now has Windows path translation via wslpath, but the 8 existing tests do not exercise it. Finish focused mocked Linux/WSL path tests (normal/space-containing Windows paths, shell=False argv, cached drive root, missing wslpath, failed/empty/relative response, unavailable mount, drive-relative and UNC behavior), fix defects in your scope, retain Windows loader behavior. Actual WSL is not installed, so do not claim WSL runtime verification.

Validation: the same TensorFlow venv python -m unittest discover -s tools/vision/training/tests -p test_train_data.py -v. Do not edit trainer or generated manifests.

## Pseudo task task_e3eafd9b16e8

Exclusive source files: tools/vision/training/pseudo_labels.py, tests/test_pseudo_labels.py, optional PSEUDO_EXECUTION.md. Moderate model selected: Antigravity Gemini 3.8 Flash Medium. The expensive full job ALREADY COMPLETED. Do not rerun or overwrite it. build/vision-training/data/pseudo_full/completion_report.json complete=true: 6647/6647 train candidates, 708 records with pseudo labels, 5939 without, 1416 files. Teacher SHA-256 312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1. Coordinator verified all IDs/group/hash/split joins and paths, plus 42 sampled tensors. Existing 10 pseudo tests pass including bounded serial/parallel equivalence. Verify the persisted result, finish execution documentation and outcome reporting; make source changes only for concrete defects. Teacher labels are not evaluation ground truth. Do not run heldout inference, full training, or raw conversion.

Validation: same venv python -m unittest discover -s tools/vision/training/tests -p test_pseudo_labels.py -v; existing full report and artifact checks.

## City label task task_a1eeea9e9db6

Exclusive files: tools/vision/training/data.py, DATA_CONTRACT.md, tests/test_data.py. Bounded model selected: Antigravity Gemini 3.8 Flash Low. Existing code already removes invented label34 mapping and tests reject34/35; 13 tests pass. Official source: https://raw.githubusercontent.com/mcordts/cityscapesScripts/master/cityscapesscripts/helpers/labels.py (vehicles26..33, licenseplate -1 ignored, no34). Coordinator scanned all3475 raw Cityscapes label masks and found zero pixels34, so full converted outputs remain valid. Verify final source/docs/test consistency and report completion. No full reconversion, generated artifact rewrite, or unrelated taxonomy changes.

Validation: same venv python -m unittest discover -s tools/vision/training/tests -p test_data.py -v; py_compile, diff check as appropriate.
