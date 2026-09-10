# AGENTS.md

Two Android apps in one Gradle build: **Field Kiosk** (`org.awana.kiosk`), a
Device Owner app that locks a budget phone down to a handful of apps for
non-technical, sometimes non-literate users, and **Field Kiosk Setup**
(`org.awana.provision`), the trainer's app that provisions those devices over a
local hotspot. `shared/` holds the wire protocol both compile.

**Read [CONTRIBUTING.md](CONTRIBUTING.md).** It has the layout, the build and
test commands, the architecture, and the list of things that look like bugs but
are deliberate. What follows is only what an agent needs on top of it.

## Do not grow logic into the receiver

`onProfileProvisioningComplete` is a thin wrapper over
`Provisioner.provision(config)` and nothing else. That is the seam the whole
provisioning path is tested through: tests call `provision()` directly with a
synthesised config, so download, verify, install, apply policy and report all
run without a camera or a setup wizard. Anything added to the receiver is
untestable by construction.

## Use both emulators

`kiosk_aosp_30` is the Device Owner and runs the policy, lock task, install and
PIN tests. `kiosk_ui_30` is clean and runs the Compose UI tests. The split is
not cosmetic: on a device where the kiosk is Device Owner, the HOME activity and
holding lock task, Compose's test harness sees no compose hierarchy at all, and
every UI test fails with "No compose hierarchies found". `ComposeSmokeTest` is
the canary — if it fails, the tests are on the wrong emulator, not broken.

`tools/test.sh` runs both. Do not conclude a UI test is broken until that smoke
test passes on the same device.

## Read the failure messages, not the count

A connected run prints only a pass/fail count. The messages are in the XML:

```sh
python3 -c "
import glob, xml.etree.ElementTree as ET
for f in glob.glob('**/build/outputs/androidTest-results/connected/debug/*.xml', recursive=True):
    r = ET.parse(f).getroot()
    for tc in r.iter('testcase'):
        for fa in list(tc): print(tc.get('name'), (fa.text or '')[:600])
"
```
