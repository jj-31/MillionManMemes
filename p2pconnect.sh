#!/bin/bash
IF=p2p-dev-wlo1
sudo wpa_cli -i $IF p2p_find > /dev/null
for i in $(seq 1 30); do
  MAC=$(sudo wpa_cli -i $IF p2p_peers | grep -Eo '^([0-9a-f]{2}:){5}[0-9a-f]{2}$' | head -1)
  if [ -n "$MAC" ]; then
    echo "Found $MAC"
    sudo wpa_cli -i $IF p2p_connect "$MAC" pbc auth go_intent=15
    exit 0
  fi
  sleep 1
done
echo "No peers found"
