// A tiny method + path router. Patterns use `:name` segments, which match one path segment.

export type Params = Record<string, string>;
type Route<H> = { method: string; re: RegExp; names: string[]; handler: H };

export class Router<H> {
	private routes: Route<H>[] = [];

	on(method: string, pattern: string, handler: H): this {
		const names: string[] = [];
		const re = new RegExp(
			"^" + pattern.replace(/:(\w+)/g, (_, n: string) => (names.push(n), "([^/]+)")) + "$",
		);
		this.routes.push({ method, re, names, handler });
		return this;
	}

	match(method: string, path: string): { handler: H; params: Params } | null {
		for (const r of this.routes) {
			if (r.method !== method) continue;
			const m = path.match(r.re);
			if (!m) continue;
			const params: Params = {};
			try {
				r.names.forEach((n, i) => (params[n] = decodeURIComponent(m[i + 1])));
			} catch {
				return null; // a malformed %-escape matches nothing
			}
			return { handler: r.handler, params };
		}
		return null;
	}
}
