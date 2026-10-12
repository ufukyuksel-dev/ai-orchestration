#!/usr/bin/env bash
# Prepare the py-holdout suite (bench/tasks/py-holdout.json): clone miguelgrinberg/microblog at the pinned commit into
# bench/.work/repos/microblog and create the virtualenv bench/.work/venvs/microblog with exactly what the app and its
# tests need. Idempotent: re-running reuses the clone and the venv.
#
# The interpreter: $PYTHON if set, else the first of python3.12, python3.11, python3 on PATH. The pinned packages
# (the 2023 pins of the repo's requirements.txt) have wheels for CPython 3.11 and 3.12.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
name=microblog
url=https://github.com/miguelgrinberg/microblog.git
sha=a975ef64864354867c88e0ed3a17ba7d17dca752
repo="$here/.work/repos/$name"
venv="$here/.work/venvs/$name"

if [ ! -d "$repo/.git" ]; then
  mkdir -p "$(dirname "$repo")"
  git clone --quiet "$url" "$repo"
fi
if ! git -C "$repo" cat-file -e "$sha^{commit}" 2>/dev/null; then
  git -C "$repo" fetch --quiet origin
fi
git -C "$repo" -c advice.detachedHead=false checkout --quiet "$sha"
echo "$name @ $(git -C "$repo" rev-parse --short HEAD)"

py="${PYTHON:-}"
if [ -z "$py" ]; then
  for candidate in python3.12 python3.11 python3; do
    if command -v "$candidate" >/dev/null 2>&1; then py="$candidate"; break; fi
  done
fi
[ -n "$py" ] || { echo "no python3 found" >&2; exit 1; }

if [ ! -x "$venv/bin/python" ]; then
  mkdir -p "$(dirname "$venv")"
  "$py" -m venv "$venv"
fi

# The repo's requirements.txt pins, minus what neither the app import graph nor tests.py needs (gunicorn, httpie,
# psycopg2-binary, aiosmtpd and their dependencies; multidict has no wheel for current Pythons).
"$venv/bin/python" -m pip install --quiet --disable-pip-version-check \
  alembic==1.12.1 Babel==2.13.1 blinker==1.7.0 certifi==2023.11.17 charset-normalizer==3.3.2 click==8.1.7 \
  dnspython==2.4.2 elastic-transport==8.10.0 elasticsearch==8.11.0 email-validator==2.1.0.post1 Flask==3.0.0 \
  flask-babel==4.0.0 Flask-HTTPAuth==4.8.0 Flask-Login==0.6.3 Flask-Mail==0.9.1 Flask-Migrate==4.0.5 \
  Flask-Moment==1.0.5 Flask-SQLAlchemy==3.1.1 Flask-WTF==1.2.1 greenlet==3.0.1 idna==3.4 itsdangerous==2.1.2 \
  Jinja2==3.1.2 langdetect==1.0.9 Mako==1.3.0 MarkupSafe==2.1.3 packaging==23.2 PyJWT==2.8.0 python-dotenv==1.0.0 \
  pytz==2023.3.post1 redis==5.0.1 requests==2.31.0 rq==1.15.1 six==1.16.0 SQLAlchemy==2.0.23 \
  typing_extensions==4.8.0 urllib3==2.1.0 Werkzeug==3.0.1 WTForms==3.1.1
"$venv/bin/python" -m pip check --disable-pip-version-check
echo "venv: $venv ($("$venv/bin/python" --version))"
