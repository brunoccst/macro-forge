"""The macro pack tool, on the starter game."""
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import macroforge_pack as mp  # noqa: E402

STARTER = ROOT / "starter"


def test_starter_pack_is_clean():
    assert mp.check(STARTER) == []


def test_pack_holds_profile_macros_and_templates(tmp_path):
    out = tmp_path / "macros.zip"
    mp.build(out, STARTER, "abc", "main")
    names = zipfile.ZipFile(out).namelist()
    assert "game.yaml" in names and "macros/basic/roll-until-six.yaml" in names
    assert "templates/common/library.yaml" in names and "pack.json" in names


def _game(tmp_path, **extra):
    import yaml
    profile = yaml.safe_load((STARTER / "game.yaml").read_text(encoding="utf-8"))
    profile.update(extra)
    (tmp_path / "game.yaml").write_text(yaml.safe_dump(profile), encoding="utf-8")


def test_unknown_group_and_missing_how(tmp_path):
    _game(tmp_path)
    (tmp_path / "macros" / "raid").mkdir(parents=True)
    (tmp_path / "macros" / "raid" / "x.yaml").write_text("name: x\ngroup: raid\nunknown: [{wait: 1}]\n")
    problems = mp.check(tmp_path)
    assert any("not one of game.yaml's groups" in p for p in problems)
    assert any("no how:" in p for p in problems)


def test_plugin_steps_describe_themselves(tmp_path):
    _game(tmp_path, plugin_steps=[])
    (tmp_path / "macros" / "basic").mkdir(parents=True)
    (tmp_path / "macros" / "basic" / "c.yaml").write_text("name: c\ngroup: basic\nunknown: [{roll_die: true}]\n")
    assert any("no how:" in p for p in mp.check(tmp_path))
    _game(tmp_path, plugin_steps=["roll_die"])
    assert mp.check(tmp_path) == []


def test_runs_unknown_macro(tmp_path):
    _game(tmp_path)
    (tmp_path / "macros" / "basic").mkdir(parents=True)
    (tmp_path / "macros" / "basic" / "a.yaml").write_text("name: a\ngroup: basic\nhow: [x]\nunknown: [{run: nope}]\n")
    assert any("runs unknown macro 'nope'" in p for p in mp.check(tmp_path))
