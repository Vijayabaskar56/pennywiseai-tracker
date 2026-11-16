// Applies migrations/ to the deployed D1 database. `cf d1 migrations apply` takes the
// database id, which lives in .deploy.env (see cloudflare.config.ts).
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";

const envFile = new URL("../.deploy.env", import.meta.url);
if (existsSync(envFile)) process.loadEnvFile(envFile);

const id = process.env.SHARED_EXPENSES_D1_ID;
if (!id || /^0{8}-/.test(id)) {
	console.error("Set SHARED_EXPENSES_D1_ID in .deploy.env first (see .deploy.env.example).");
	process.exit(1);
}
execFileSync("cf", ["d1", "migrations", "apply", id], {
	stdio: "inherit",
	cwd: new URL("..", import.meta.url),
});
