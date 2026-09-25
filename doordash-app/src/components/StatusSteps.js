import { Steps } from "antd";
import { STATUS_LABELS } from "../api";

const FLOW = ["PLACED", "PAID", "ACCEPTED", "READY", "COMPLETED"];

/** Index of the current step, or -1 for a cancelled order. */
export const stepIndex = (status) => FLOW.indexOf(status);

const StatusSteps = ({ status }) => {
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
