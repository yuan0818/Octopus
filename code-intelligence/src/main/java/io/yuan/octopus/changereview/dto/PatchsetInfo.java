package io.yuan.octopus.changereview.dto;

/**
 * Latest patchset info resolved from Gerrit REST (used by manual trigger).
 *
 * @param patchsetNumber patchset number
 * @param branch         target branch
 * @param commit         patchset commit SHA (current_revision)
 */
public record PatchsetInfo(int patchsetNumber, String branch, String commit) {
}
