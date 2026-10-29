#!/usr/bin/env python3
"""Import a Google Cloud Desktop app download into ignored local build configuration."""
import json
import os
import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit('Usage: python3 packaging/import-google-client.py /path/to/client_secret.json')

source = json.loads(Path(sys.argv[1]).expanduser().read_text())
client = source.get('installed')
if not isinstance(client, dict) or not client.get('client_id', '').endswith('.apps.googleusercontent.com') or not client.get('client_secret'):
    raise SystemExit('Use the JSON downloaded for an OAuth Desktop app client.')

destination = Path(__file__).resolve().parent / '.google-client.json'
descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(descriptor, 'w') as output:
    json.dump({'clientId': client['client_id'], 'clientSecret': client['client_secret']}, output)
    output.write('\n')
destination.chmod(0o600)
print('Google Desktop OAuth client imported into local build configuration. Credentials were not printed.')
