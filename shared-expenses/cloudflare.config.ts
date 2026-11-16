// Deploy configuration for `cf deploy` (README.md). Tests and `npm run dev` still read
// wrangler.jsonc, so keep the bindings in both files in step.
//
// Each deployment's own values come from the environment, or from a `.deploy.env` file
// next to this one (gitignored; see .deploy.env.example):
//   SHARED_EXPENSES_D1_ID   the id `cf d1 create` printed
//   SHARED_EXPENSES_DOMAIN  optional custom domain, e.g. shared.example.com
//   BASE_URL                optional; defaults to https://<domain>, used to build invite links
import { existsSync } from "node:fs";
import { bindings, defineConfig, triggers } from "cf/config";

const envFile = new URL("./.deploy.env", import.meta.url);
if (existsSync(envFile)) process.loadEnvFile(envFile);

const databaseId = process.env.SHARED_EXPENSES_D1_ID ?? "00000000-0000-0000-0000-000000000000";
const domain = process.env.SHARED_EXPENSES_DOMAIN || undefined;
const baseUrl = process.env.BASE_URL ?? (domain ? `https://${domain}` : "http://localhost:8790");

export default defineConfig({
	worker: {
		name: "pennywise-shared-expenses",
		compatibilityDate: "2026-11-01",
		entrypoint: "src/index.ts",
		// With a custom domain the workers.dev address is turned off, so the app has one address.
		...(domain ? { domains: [domain], workersDev: false } : {}),
		// Logs no request bodies, amounts or names.
		observability: {
			enabled: true,
			logs: {
				invocationLogs: false,
			},
		},
		// Recurring expenses come due once a day, just after midnight UTC.
		triggers: [
			triggers.scheduled({
				schedule: "5 0 * * *",
			}),
		],
		env: {
			// The public https:// address the Worker is reached at, used to build invite links.
			BASE_URL: bindings.text(baseUrl),
			// Tables come from migrations/: `npm run migrate` (cf d1 migrations apply).
			DB: bindings.d1({
				name: "pennywise-shared-expenses",
				id: databaseId,
			}),
			// Namespaces must be unique within a Cloudflare account; the AI relay uses 840101 to 840103.
			REGISTER_LIMIT: bindings.rateLimit({
				namespace: "840201",
				simple: {
					limit: 5,
					period: 60,
				},
			}),
			INVITE_LIMIT: bindings.rateLimit({
				namespace: "840202",
				simple: {
					limit: 10,
					period: 60,
				},
			}),
			API_LIMIT: bindings.rateLimit({
				namespace: "840203",
				simple: {
					limit: 300,
					period: 60,
				},
			}),
		},
	},
});
