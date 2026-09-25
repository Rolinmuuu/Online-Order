import { Steps } from "antd";
import { STATUS_LABELS } from "../api";
import type { OrderStatus } from "../types";

const FLOW: OrderStatus[] = ["PLACED", "PAID", "ACCEPTED", "READY", "COMPLETED"];

/** Index of the current step, or -1 for a cancelled order. */
export const stepIndex = (status: OrderStatus): number => FLOW.indexOf(status);

const StatusSteps = ({ status }: { status: OrderStatus }) => {
  if (status === "CANCELLED") return null;
  return (
    <Steps
      size="small"
      current={stepIndex(status)}
      items={FLOW.map((s) => ({ title: s === "PLACED" ? "Placed" : STATUS_LABELS[s] }))}
    />
  );
};

export default StatusSteps;
