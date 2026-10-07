/**
 * Serving plan.
 *
 * Prints what the host can actually run before anyone downloads weights, and
 * labels every requirement as published rather than measured. It also states,
 * explicitly, that no default has been chosen.
 *
 * Usage: node dist/src/scripts/servingPlan.js
 */

import * as os from 'node:os';

import { MODEL_CATALOG, candidateModels, selectDefaultModel } from '../platform/serving/modelCatalog';
import { detectNvidiaSmi } from '../platform/serving/resourceSampler';

async function main(): Promise<void> {
  const out = process.stdout;
  out.write('model serving plan\n');
  out.write('===================\n\n');

  const gpu = await detectNvidiaSmi();
  out.write('this host\n');
  out.write('---------\n');
  out.write(gpu ? `  GPU: ${gpu}\n` : '  GPU: none detected (nvidia-smi absent or no NVIDIA device)\n');
  const ramGb = Math.round((os.totalmem() / 1024 ** 3) * 10) / 10;
  out.write(`  RAM: ${ramGb} GB total\n`);
  out.write(`  CUDA runtime: not probed by this script; check with \`nvidia-smi\` and \`nvcc --version\`\n\n`);

  out.write('candidates (requirements are PUBLISHED figures, not measured on this host)\n');
  out.write('---------------------------------------------------------------------------\n');
  for (const spec of MODEL_CATALOG) {
    out.write(`  ${spec.id}\n`);
    out.write(`    model       ${spec.modelId} @ ${spec.revision}\n`);
    out.write(`    license     ${spec.license} (commercial use: ${spec.licenseAllowsCommercialUse ? 'yes' : 'no'})\n`);
    out.write(`    quant       ${spec.quantization}\n`);
    out.write(`    serving     ${spec.servingStyles.join(', ')} (default ${spec.defaultStyle}, port ${spec.defaultPort})\n`);
    out.write(
      `    published   vram ~${spec.requirements.vramGbTypical}GB (min ${spec.requirements.vramGbMinimum}GB), ` +
        `ram >=${spec.requirements.ramGbMinimum}GB, download ~${spec.requirements.downloadGb}GB\n`,
    );
    out.write(`    install     ${spec.install.join(' && ')}\n`);
    out.write(`    health      ${spec.serve.join('  |  ')}\n`);
    out.write(`    notes       ${spec.notes}\n\n`);
  }

  out.write('what this host can serve right now\n');
  out.write('---------------------------------\n');
  const feasible = gpu ? candidateModels({ availableRamGb: ramGb }) : [];
  if (!gpu) {
    out.write('  none: no usable GPU was detected on this host.\n');
    out.write('  Every candidate is therefore blocked here, not merely slow.\n');
    out.write('  CPU-only execution is not offered: a 4B model at these sizes would translate a\n');
    out.write('  chapter far slower than a reader will wait.\n');
  } else if (feasible.length === 0) {
    out.write(`  none: a GPU is present but no candidate fits within ${ramGb} GB of RAM.\n`);
  } else {
    for (const spec of feasible) {
      out.write(`  ${spec.id}\n`);
    }
  }

  out.write('\ndefault model\n');
  out.write('-------------\n');
  try {
    selectDefaultModel();
  } catch (error) {
    out.write(`  UNDECIDED — ${(error as Error).message}\n`);
  }
  out.write(
    '\n  Selection requires a real run: `npm run benchmark -- --split test` on a GPU host,\n' +
      '  followed by a decision recorded in docs/model-selection.md.\n',
  );
}

main().catch((error: unknown) => {
  process.stderr.write(`serving plan failed: ${String((error as Error)?.message ?? error)}\n`);
  process.exit(1);
});
