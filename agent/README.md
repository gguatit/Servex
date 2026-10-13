# servex-agent

Reference agent for Servex. Python 3.8+, standard library only.

## Run

```sh
cp servex-agent.json.example servex-agent.json
# edit servex-agent.json and set a long random token
python3 servex-agent.py --config servex-agent.json
```

HTTP server on port 8765 (default). See `../docs/AGENT.md` for the API.
