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



def test_routine_runs_the_macro_picked_in_an_option(tmp_path):
    _game(tmp_path)
    d = tmp_path / "macros" / "basic"
    d.mkdir(parents=True)
    (d / "a.yaml").write_text("name: a\ngroup: basic\nhow: [x]\nunknown: [{wait: 1}]\n")
    (d / "b.yaml").write_text(
        "name: b\ngroup: basic\nparams:\n  pick: {default: none, choices: [none, a]}\n"
        "steps: [a, {macro: '{pick}', params: {fast: true}}]\nalways: [a]\n")
    assert mp.check(tmp_path) == []
    (d / "b.yaml").write_text(
        "name: b\ngroup: basic\nparams:\n  pick: {default: a, choices: [a, nope, b]}\n  free: {default: a}\n"
        "steps: [{macro: '{pick}'}, {macro: '{free}'}, {macro: '{missing}'}, {macro: a, params: [1]}]\n")
    problems = mp.check(tmp_path)
    assert any("choice 'nope' is not a macro" in p for p in problems)
    assert any("choice 'b' runs itself" in p for p in problems)
    assert any("picked in 'free'" in p for p in problems)
    assert any("picked in 'missing'" in p for p in problems)
    assert any("params must be a map" in p for p in problems)


def test_options_follow_known_globals(tmp_path):
    _game(tmp_path, globals={"team": {"default": "current", "choices": ["current", 1, 2]},
                             "bad": {"default": 9, "choices": [1, 2]}})
    (tmp_path / "macros" / "daily").mkdir(parents=True)
    (tmp_path / "macros" / "daily" / "x.yaml").write_text(
        "name: x\ngroup: daily\nhow: [Go]\nparams:\n  team: {global: team}\n  speed: {global: speed}\nunknown: [{wait: 1}]\n")
    problems = mp.check(tmp_path)
    assert any("unknown global 'speed'" in p for p in problems)
    assert not any("'team'" in p for p in problems)
    assert any("global 'bad': default" in p for p in problems)


def test_routine_required_and_always_name_its_steps(tmp_path):
    _game(tmp_path)
    (tmp_path / "macros" / "basic").mkdir(parents=True)
    for n in ("a", "b"):
        (tmp_path / "macros" / "basic" / f"{n}.yaml").write_text(f"name: {n}\ngroup: basic\nhow: [x]\n")
    (tmp_path / "macros" / "basic" / "r.yaml").write_text(
        "name: r\ngroup: basic\nsteps: [a, b]\nrequired: [a]\nalways: [c]\n")
    problems = mp.check(tmp_path)
    assert any("always: 'c' is not one of its steps" in p for p in problems)
    assert not any("required:" in p for p in problems)


def test_max_visits_names_its_screens(tmp_path):
    _game(tmp_path)
    (tmp_path / "macros" / "basic").mkdir(parents=True)
    (tmp_path / "macros" / "basic" / "m.yaml").write_text(
        "name: m\ngroup: basic\nhow: [x]\nscreens: [title, field]\nmax_visits: {title: 3, home: 2, field: 0}\n")
    problems = mp.check(tmp_path)
    assert any("max_visits: 'home' is not one of its screens" in p for p in problems)
    assert any("max_visits: field: 0" in p for p in problems)
    assert not any("max_visits: 'title'" in p or "max_visits: title" in p for p in problems)


# a quoting mistake like the one that once broke every connector call on the phone
BROKEN = "a: '\"Confirming Updates\" under the title screen's progress bar'\n"


def test_a_broken_yaml_file_anywhere_in_the_pack_is_a_problem(tmp_path):
    _game(tmp_path)
    (tmp_path / "macros" / "basic").mkdir(parents=True)
    (tmp_path / "macros" / "basic" / "a.yaml").write_text("name: a\ngroup: basic\nhow: [x]\nunknown: [{wait: 1}]\n")
    lib = tmp_path / "templates" / "common"
    lib.mkdir(parents=True)
    (lib / "library.yaml").write_text("templates:\n  x:\n    notes: " + BROKEN.split(": ", 1)[1])
    problems = mp.check(tmp_path)
    assert len(problems) == 1 and problems[0].startswith("templates/common/library.yaml: not valid YAML")
    try:
        mp.build(tmp_path / "macros.zip", tmp_path)
        raise AssertionError("a pack with a broken file must not be built")
    except SystemExit as e:
        assert "library.yaml" in str(e)


def test_a_broken_macro_or_game_profile_is_reported_not_crashed(tmp_path):
    _game(tmp_path)
    (tmp_path / "macros" / "basic").mkdir(parents=True)
    (tmp_path / "macros" / "basic" / "bad.yaml").write_text("name: bad\n" + BROKEN)
    assert any(p.startswith("macros/basic/bad.yaml: not valid YAML") for p in mp.check(tmp_path))
    (tmp_path / "game.yaml").write_text(BROKEN)
    assert mp.check(tmp_path)[0].startswith("game.yaml: not valid YAML")


def test_template_library_entries_are_checked(tmp_path):
    _game(tmp_path)
    lib = tmp_path / "templates" / "common"
    lib.mkdir(parents=True)
    (lib / "library.yaml").write_text("templates:\n  ok: {region: [0, 0, 1, 1], ref: [2400, 1080]}\n  bad: {region: [0, 0]}\n")
    problems = mp.check(tmp_path)
    assert "templates/common/library.yaml: template bad: region must be [x, y, w, h]" in problems
    assert not any("template ok" in p for p in problems)


def test_check_yaml_walks_the_whole_repo(tmp_path):
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs" / "x.yml").write_text(BROKEN)
    (tmp_path / "ok.yaml").write_text("a: 1\n")
    (tmp_path / "node_modules").mkdir()
    (tmp_path / "node_modules" / "skip.yaml").write_text(BROKEN)
    assert [p.split(":")[0] for p in mp.check_yaml(tmp_path)] == ["docs/x.yml"]
    assert mp.check_yaml(ROOT) == []


def test_search_options_need_choices(tmp_path):
    _game(tmp_path)
    (tmp_path / "macros" / "daily").mkdir(parents=True)
    (tmp_path / "macros" / "daily" / "x.yaml").write_text(
        "name: x\ngroup: daily\nhow: [Go]\nparams:\n  a: {default: p, type: search, choices: [p, q]}\n"
        "  b: {default: p, type: search}\nunknown: [{wait: 1}]\n")
    problems = mp.check(tmp_path)
    assert any("'b' is type: search but has no choices" in p for p in problems)
    assert not any("'a'" in p for p in problems)
