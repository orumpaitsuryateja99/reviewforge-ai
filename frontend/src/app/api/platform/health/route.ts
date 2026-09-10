import { NextResponse } from "next/server";
import { backendUrl } from "@/lib/backend-url";

export const dynamic = "force-dynamic";

export async function GET() {
  try {
    const response = await fetch(`${backendUrl()}/api/v1/health`, {
      cache: "no-store",
      signal: AbortSignal.timeout(3000),
    });

    if (!response.ok) {
      return NextResponse.json({ status: "DOWN" }, { status: 503 });
    }

    return NextResponse.json(await response.json(), { status: 200 });
  } catch {
    return NextResponse.json({ status: "DOWN" }, { status: 503 });
  }
}
