# KH1 live address check (PC, Steam 1.0.0.2, 2026-11-04)

Save: Deep Jungle, Tent (world 5, room 14). Party Sora / Donald / Goofy. Farthest world: Neverland.
Read with `tools/probe/probe.ps1` against the v0.0.3 DLL. On-screen values confirmed by the user.

## Party: use the battle block (current and effective max)

| Member | Battle block | Bytes (u32 HP, maxHP, MP, maxMP) | On screen |
|---|---|---|---|
| Sora | `0x2D5CC4C` | 39 39 05 05 → 57/57, 5/5 | 57/57 |
| Donald (slot 1) | `0x2D5CD4C` (+0x100) | 2a 2a 08 08 → 42/42, 8/8 | 42/42 |
| Goofy (slot 2) | `0x2D5CE4C` (+0x200) | 3f 3f 04 04 → 63/63, 4/4 | 63/63 |

- Battle block: stride `0x100`, 3 members in party order, u32 fields at +0 HP, +4 maxHP, +8 MP, +0xC maxMP. **live**.
- Party slots `0x2DE97EF` = `01 02 03 02`: byte 0 = slot 1 char id (1 Donald), byte 1 = slot 2 (2 Goofy). Bytes 2-3
  unknown (not party order). **live** for bytes 0-1.
- The stats table `0x2DE9364 + id*0x74` (level, HP, maxHP, MP, maxMP) holds **base** maxima, not effective ones:
  Sora 2d 39 33 → max 51 vs real 57; Donald MP 08 with "max" 06. Use it for level only. **live**.

## Chests: formula confirmed by pattern

Chest bitfield read at `0x2DE9A90`+128 and `0x2DEB1C0`+80 against `kh1-data-draft/chests.json`:
Neverland 11 / 12 opened (farthest world), Hollow Bastion 0 / 38, End of the World 0 / 26 (not reached).
The opened = `(u8[address] >> bit) & 1` rule is **live** (pattern). The Hollow Bastion Emblem Piece uses a value rule
(`u8[0x2DEB20F] >= 2`), not a bit.

## Story bytes: base `0x2DEA864` confirmed

`0x2DEA860` +20 = `aa 56 24 04 | 64 6e 3c 32 82 46 78 00 78 78 0a 00 …`. From `0x2DEA864`: index 7 (unused) is 0,
Neverland (9) = 0x78, Hollow Bastion (10) = 0x0a, End of the World (11) = 0: matches the save. **live**.
