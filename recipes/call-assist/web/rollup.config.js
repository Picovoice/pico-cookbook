'use strict';
const path = require('path');
const { nodeResolve } = require('@rollup/plugin-node-resolve');
const commonjs = require('@rollup/plugin-commonjs');
const typescript = require('rollup-plugin-typescript2');
const { babel } = require('@rollup/plugin-babel');
const { DEFAULT_EXTENSIONS } = require('@babel/core');

const extensions = [...DEFAULT_EXTENSIONS, '.ts'];

export default {
  input: [path.resolve(__dirname, 'src', 'index.ts')],
  output: [
    {
      file: path.resolve(__dirname, 'public', 'index.js'),
      format: 'iife',
      name: 'Picovoice',
      sourcemap: false,
    },
  ],
  plugins: [
    nodeResolve({ extensions }),
    commonjs(),
    typescript({
      typescript: require('typescript'),
      cacheRoot: path.resolve(__dirname, '.rts2_cache'),
      clean: true,
    }),
    babel({
      extensions: extensions,
      babelHelpers: 'runtime',
      exclude: '**/node_modules/**',
    }),
  ],
};
