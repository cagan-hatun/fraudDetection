package com.fraud.project.service;

public record DlqRedriveResult(int found, int succeeded, int failed) {
}
