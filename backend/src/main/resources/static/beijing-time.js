"use strict";

const BEIJING_TIME_ZONE = "Asia/Shanghai";

function beijingDateTime(value) {
  if (value == null || value === "") return "—";
  let source = String(value);
  if (!/[zZ]|[+-]\d{2}:?\d{2}$/.test(source)) source += "+08:00";
  const date = new Date(source);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat("sv-SE", {
    timeZone: BEIJING_TIME_ZONE,
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"
  }).format(date);
}

function beijingDateTimeInput(value) {
  const formatted = beijingDateTime(value);
  return formatted === "—" ? "" : formatted.replace(" ", "T").slice(0, 16);
}

function beijingDateTimeInstant(value) {
  if (!value) return null;
  const source = value.length === 16 ? value + ":00" : value;
  return new Date(source + "+08:00").toISOString();
}

function beijingDateTimeFilter(value) {
  return value ? value + (value.length === 16 ? ":00" : "") + "+08:00" : "";
}
