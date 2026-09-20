# PR #139 / #140 review

[可编辑 M3E Canvas](https://lnkiai.github.io/m3e-canvas/#docz=xZVbTxNBFMe_ymaeq-3sLhX7pIk-GGJCfNAH5WHandKN293N7hSKhISiUtCCl3iJSkUMBmNUwICsXBM_QfkMdnfLk1_BM10uW5TWQI196HZOz8785sx__mcQMZVpFCXQZUNXU0T4viJ0XxGwdFaICliOCRbtU2k_iqCkpdI05FW3yv7TF_7kgjc17jq33cXhn-sl78lXf2nOnZjxx4v83xcbP9dfeaXxncefId8rFdzJaXdh1Z13IO7OvqzNlGpbr73Jd96zojcz5i7erTqffgyPwDppi2Q5j5kxdApjUyMsbVhZCBFdsQxV4UGiUcZoFx3gmTnL1Hgqy1D-6iBSiHUTJdJEsymAGyxz2VCojRLMykEgZejMIjaDV20GcxKLT2lniMnXtYycrlAeSUMezxmwGc3COGsw1dAhQvOmRW1b7aNoaBcYJr8-iIAtgWKQqgd72PlQqs0X_DeFqjPpzo_CD3ds1F-7Axl5lIhF0ED9O9nLl8lZaZLi29ANRv95oYciu7j4APcaTV44f5VLIHgzmDeAleP_F7cngnrhaMxwoU_FAjYcr6PJnRFE8ipkwCiCVDi137JvqjofMJpnMNJIkmrhc-J758DFRzvPl2orZa4L9RZsTxS5kDRlX1VqiktBz2laBPURSyV1raRVTQPtDPXslzd2CjdAYllsTokPKDXVZpcC7e2RXsyTLIgdLidJpUCp7BwNIqcNq3efVuKHdUxcsQFXlOLNccUji4pFSZA74sLhj9gp-HOP21FZqQFVkmLNUaUjUd3Ru7XNb3DeAo4pJkix6tyrOvcDDQidUG1ZMdtBLDcKVpSbE8tNtRB2FC5cuGy15w-41SwUdspvw9dMkOKhDZxEHh0NO-jALe5cx9E1r3OHKi_WO069_F55xn_1uX4YJ6863rMJ-czf-ARu4hNdlHYT2xZ2jTIqVFcfubPF6tpGOzBxI2YLp8AtnCLDmGknotGQP0T7aVIhfW3QAd6ziT3YFj6Bwz7xB1j_yXtvbMV9OAE1DWTdFkipEbKFQ-CwQyRzjMEyB4iV6e3hymblS2UZns72yHbphr5dgMhyZaniVDbguQHP5baAy4cU29wocNgoDkm26szyTg7tdOqjWy4GHRU6c9Cuof1Cuw6cImjaxxdyz9Av)

保留现有布局，仅审核卡片密度与多行按钮文字对齐。

## Review and merge

- #139: keep the reduced card padding/spacing; restore the four group-action size modifiers to 36dp (PR originally reduced them to 32dp despite its description). Commit c460665b.
- #140: original patch only covered the KeePass WebDAV browser. Also center the shared CloudBackupPrimaryButton label, covering WebDAV backup. Commit a62408ed.
- Merged #139 as a2a65516 and #140 as b8857fef. Published release notes in 7a179869.
- Original PR builds compiled successfully (#140 preview build passed; #139 MDBX runtime job prebuilt instrumentation APKs successfully). Existing lint failures concern PasswordViewModel indentation and ExtraTranslation in files unchanged by these PRs. CodeQL runner received shutdown/cancellation. #139 native runtime failed creating an MDBX2 vault; that issue is not fixed by these UI changes.
- Reviewed the final diffs and main/F-Droid parity. The two small follow-up patches were not rebuilt or device-tested locally in this pass; original-head CI evidence does not certify these new commits. The Canvas is a review sketch, not a native screenshot.
