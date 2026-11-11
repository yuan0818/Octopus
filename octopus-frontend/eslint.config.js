import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // 已知技术债（UI P2：统一数据获取 hook、补全类型）：暂降为 warning，其余规则在 CI 中阻断合并。
      // 新代码请不要新增这两类问题；清理完成后删除这两行恢复为 error。
      '@typescript-eslint/no-explicit-any': 'warn',
      'react-hooks/set-state-in-effect': 'warn',
      // 允许用 { omitted: _x, ...rest } 的方式显式丢弃字段
      '@typescript-eslint/no-unused-vars': ['error', { ignoreRestSiblings: true, argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },
])
