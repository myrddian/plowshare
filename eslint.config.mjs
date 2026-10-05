import js from '@eslint/js';
import tseslint from 'typescript-eslint';

const files = [
  'sdk/*/src/**/*.ts',
  'plowshare-*/src/**/*.ts',
  'plowshare-*/vite.config.ts',
];
export default tseslint.config(
  {
    ignores: [
      '**/build/**',
      '**/build-tests/**',
      '**/node_modules/**',
      'sdk/typescript/src/operations/operation-schemas.ts',
      'plowshare-desktop/src/request-schemas.ts',
      'plowshare-console/src/transport-schemas.ts',
    ],
  },
  {
    files,
    extends: [
      js.configs.recommended,
      ...tseslint.configs.recommendedTypeChecked,
    ],
    languageOptions: {
      parserOptions: {
        tsconfigRootDir: import.meta.dirname,
        project: [
          'sdk/typescript/src/*/tsconfig.test.json',
          'sdk/typescript/src/tsconfig.test.json',
          'sdk/node/tsconfig.lint.json',
          'plowshare-cli/tsconfig.test.json',
          'plowshare-mcp/tsconfig.test.json',
          'plowshare-tui/tsconfig.lint.json',
          'plowshare-console/tsconfig.json',
          'plowshare-desktop/tsconfig.json',
        ],
      },
    },
    linterOptions: { reportUnusedDisableDirectives: 'error' },
    rules: {
      // Underscore names document intentionally unused capability arguments or omitted DTO fields.
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
        },
      ],
      '@typescript-eslint/consistent-type-imports': 'error',
      '@typescript-eslint/no-base-to-string': 'error',
      '@typescript-eslint/no-floating-promises': [
        'error',
        { ignoreVoid: false },
      ],
      '@typescript-eslint/no-misused-spread': 'error',
      '@typescript-eslint/use-unknown-in-catch-callback-variable': 'error',
      '@typescript-eslint/switch-exhaustiveness-check': [
        'error',
        { considerDefaultExhaustiveForUnions: true },
      ],
    },
  },
  {
    // Async stubs implement a Promise-returning capability without doing I/O.
    // Production implementations retain require-await; tests still enforce
    // unsafe values, owned promises and the same compiler policy.
    files: ['**/*.test.ts', '**/*.test-support.ts'],
    rules: { '@typescript-eslint/require-await': 'off' },
  },
);
