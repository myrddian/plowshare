import { describe, expect, it } from 'vitest'
import { allowed, reservedSegment } from './fence.ts'

describe('allowed', () => {
    it('mirrors SyncRules.allowed: hidden segments need the allowlist, reserved ones never pass', () => {
        expect(allowed('src/a.ts', [])).toBe(true)
        expect(allowed('.env', [])).toBe(false)
        expect(allowed('logs/.gitkeep', [])).toBe(false)
        expect(allowed('.eslintrc', ['.eslintrc'])).toBe(true)
        expect(allowed('.github/workflows/ci.yml', ['.github/'])).toBe(true)
        expect(allowed('.githubx/a', ['.github/'])).toBe(false)
        expect(allowed('.git/config', ['.git/'])).toBe(false)
        expect(allowed('sub/.plowshare/project', ['sub/.plowshare/'])).toBe(false)
    })
})

describe('reservedSegment', () => {
    it('flags .git/.plowshare regardless of case, trailing dots/spaces, or the 8.3 alias', () => {
        expect(reservedSegment('.git')).toBe(true)
        expect(reservedSegment('.GIT')).toBe(true)
        expect(reservedSegment('.git.')).toBe(true)
        expect(reservedSegment('.git ')).toBe(true)
        expect(reservedSegment('git~1')).toBe(true)
        expect(reservedSegment('.gitignore')).toBe(false)
        expect(reservedSegment('.github')).toBe(false)
    })

    it('folds unicode ignorables, NFC, and an NTFS alternate-data-stream suffix', () => {
        expect(reservedSegment('.gi‌t')).toBe(true)
        expect(reservedSegment('.GIT::$INDEX_ALLOCATION')).toBe(true)
        expect(reservedSegment('.git﻿')).toBe(true)
        expect(reservedSegment('.PLOWSHARE.')).toBe(true)
        expect(reservedSegment('.github')).toBe(false)
        expect(reservedSegment('.gitignore')).toBe(false)
    })
})
