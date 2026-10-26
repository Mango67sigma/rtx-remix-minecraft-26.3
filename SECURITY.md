# Security

This repository is an unofficial community fork of NVIDIA's RTX Remix for
Minecraft, ported to Minecraft 26.3. It is not affiliated with, endorsed by, or
supported by NVIDIA.

Report vulnerabilities in the fork's own code (the mod, its tools, and its
installer) to the fork, through GitHub's private vulnerability reporting at
<https://github.com/Mango67sigma/rtx-remix-minecraft-26.3/security/advisories/new>.
Please do not report them through public GitHub issues, and do not report them
to NVIDIA, which does not maintain this repository.

Please include the following information:

- The mod file name from the `mods` folder, which carries the version and the
  commit it was built from
- Type of vulnerability and its impact
- Instructions to reproduce the vulnerability, and proof-of-concept or exploit
  code if you have it

## Reporting a Vulnerability in the RTX Remix Runtime or Another NVIDIA Product

This section applies only to the RTX Remix runtime (dxvk-remix) and other NVIDIA
products, not to this repository. To report a potential security vulnerability
in any NVIDIA product, use NVIDIA's own channels:

- Web: [Security Vulnerability Submission Form](https://www.nvidia.com/object/submit-security-vulnerability.html)
- E-Mail: psirt@nvidia.com
  - NVIDIA encourages the use of the following PGP key for secure email
    communication: [NVIDIA public PGP Key for communication](https://www.nvidia.com/en-us/security/pgp-key)
  - Please include the following information:
    - Product or component name: **RTX Remix runtime**, and the `DXVK_Remix:`
      line from the top of `rtx-remix\logs\remix-dxvk.log`, which gives its
      version
    - Type of vulnerability and its impact
    - Instructions to reproduce the vulnerability, and proof-of-concept or
      exploit code if you have it

NVIDIA currently does not have a bug bounty program, but it does offer
acknowledgement when an externally reported security issue is addressed under
its coordinated vulnerability disclosure policy. See the
[Product Security Incident Response Team (PSIRT)](https://www.nvidia.com/en-us/security/psirt-policies/)
policies page for more information.

## Which Project

This repository is the Minecraft mod: the code that reads Minecraft state and
hands it to the RTX Remix runtime, the settings screen, the gameplay-driven
lighting profiles, and the Windows installer under `packaging/`. A crash, a
driver fault, or a memory-safety problem inside the runtime itself belongs to
[dxvk-remix](https://github.com/NVIDIAGameWorks/dxvk-remix); if the same
problem reproduces in another Remix title, it is a runtime issue. Report a
problem in this repository through GitHub's private vulnerability reporting and
a runtime problem through NVIDIA's channels, both described above. If you
cannot tell which it is, report it here, and the maintainers will say where it
belongs.

## NVIDIA Product Security

For security concerns about NVIDIA products, including the RTX Remix runtime,
please visit NVIDIA's Product Security portal at
https://www.nvidia.com/en-us/security
