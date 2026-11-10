"""本地 RexUniNLU 模型加载与推理适配。

官方 ms_wrapper.py 把模型目录写死为 ``damo/...``，这里直接复用模型仓库内的
``rex`` 代码，并把本地目录显式传给 tokenizer、config 和权重加载。
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import sys
from pathlib import Path
from typing import Any

_PREFIX_TOKEN = "[PREFIX]"
_TYPE_TOKEN = "[TYPE]"
_CLASSIFY_TOKEN = "[CLASSIFY]"
_MULTI_CLASSIFY_TOKEN = "[MULTICLASSIFY]"


class LocalRexClassifier:
    """线程外串行调用的本地分类器。"""

    def __init__(self, model: Any, trainer: Any, tokenizer: Any) -> None:
        self._model = model
        self._trainer = trainer
        self._tokenizer = tokenizer

    def predict_raw(self, text: str, schema: dict[str, Any]) -> dict[str, Any]:
        """调用训练器预测并屏蔽上游 print 输出。"""
        with contextlib.redirect_stdout(io.StringIO()):
            output = self._trainer.prediction_step(
                self._model,
                {"text": text, "schema": schema},
                prediction_loss_only=False,
                do_pred=True,
            )
        return {"output": output}

    def __call__(self, text: str, labels: list[str], mode: str = "tags") -> dict[str, Any]:
        """情感用单标签 [CLASSIFY]；题材标签用多标签 [MULTICLASSIFY]。"""
        token = _CLASSIFY_TOKEN if mode == "sentiment" else _MULTI_CLASSIFY_TOKEN
        prompt = f"{token}{','.join(labels)}|{text}"
        return self.predict_raw(prompt, {label: None for label in labels})


def load_classifier(model_dir: Path, device: str = "cpu") -> LocalRexClassifier:
    """加载本地模型；仅支持 CPU（官方 wrapper 也强制 CPU）。"""
    model_dir = Path(model_dir).resolve()
    if not (model_dir / "pytorch_model.bin").is_file():
        raise FileNotFoundError(f"模型权重不存在：{model_dir}")

    # 1. 以包形式加载模型目录下的 ms_wrapper.py，使 .rex 相对导入可用
    package_name = "rex_local_model"
    spec = importlib.util.spec_from_file_location(
        package_name,
        model_dir / "ms_wrapper.py",
        submodule_search_locations=[str(model_dir)],
    )
    if spec is None or spec.loader is None:
        raise ImportError("无法加载 ms_wrapper.py")
    wrapper_module = importlib.util.module_from_spec(spec)
    sys.modules[package_name] = wrapper_module
    spec.loader.exec_module(wrapper_module)

    # 2. 延迟导入，避免单元测试加载 torch
    import torch
    import transformers
    from transformers import AutoConfig, AutoTokenizer

    # RexModel 会先用 Rex 权重初始化 DeBERTa 底座，产生大量“未初始化”警告；
    # 实际权重随后由 load_state_dict 加载，这里只保留错误日志。
    transformers.logging.set_verbosity_error()

    from rex_local_model.rex.arguments import get_args  # type: ignore[import-not-found]
    from rex_local_model.rex.data_utils import data_loader, token_config  # type: ignore[import-not-found]
    from rex_local_model.rex.model.model import RexModel  # type: ignore[import-not-found]
    from rex_local_model.rex.Trainer.trainer import RexModelTrainer  # type: ignore[import-not-found]
    from rex_local_model.rex.Trainer.utils import compute_metrics  # type: ignore[import-not-found]

    # 3. HfArgumentParser 会读取 sys.argv，临时清空避免 uvicorn 参数干扰
    original_argv = sys.argv
    sys.argv = [original_argv[0] if original_argv else "rex"]
    try:
        data_args, training_args, model_args = get_args()
    finally:
        sys.argv = original_argv

    training_args.bert_model_dir = str(model_dir)
    training_args.load_checkpoint = str(model_dir)
    training_args.no_cuda = device != "cuda"
    training_args.fp16 = False

    tokenizer = AutoTokenizer.from_pretrained(str(model_dir))
    # 逐个 add_tokens，确保四个特殊 token 的 ID 顺序与权重训练时一致；
    # add_special_tokens 会改变 additional_special_tokens 的顺序，导致 embedding 错位。
    special_tokens = [
        token_config.PREFIX_TOKEN,
        token_config.TYPE_TOKEN,
        token_config.CLASSIFY_TOKEN,
        token_config.MULTI_CLASSIFY_TOKEN,
    ]
    for token in special_tokens:
        tokenizer.add_tokens([token], special_tokens=True)
    tokenizer._additional_special_tokens = special_tokens
    config = AutoConfig.from_pretrained(str(model_dir))
    model = RexModel(config, training_args, model_args)
    state_dict = torch.load(
        model_dir / "pytorch_model.bin",
        map_location=torch.device("cpu"),
    )
    model.load_state_dict(state_dict, strict=False)
    model.eval()

    loader = data_loader.UIEDataLoader(
        data_args,
        tokenizer,
        data_args.data_path,
        training_args.local_rank,
        training_args.world_size,
        training_args.no_cuda,
    )
    trainer = RexModelTrainer(
        model,
        training_args,
        loader.get_collate_fn(),
        tokenizer=tokenizer,
        compute_metrics=compute_metrics,
    )
    trainer.rex_dl = loader
    trainer.data_args = data_args
    return LocalRexClassifier(model, trainer, tokenizer)
