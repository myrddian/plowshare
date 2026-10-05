// Add "script": "office.mjs" to the house binding to use these handlers.
// Threshold edges, held duration, units and cooldowns remain enforced by the route.
export default {
  onEvent(event, ctx) {
    if (!['state_changed', 'threshold.held'].includes(event.type) || event.alias !== 'office.temperature'
        || event.resync || event.availability !== 'available') return [];
    const temperature = Number(event.state);
    if (!Number.isFinite(temperature) || temperature <= 28) return [];
    return [ctx.startPipeline('office_heat', { evidence: event }, { key: 'office-heat' })];
  },
  onCompletion(run, ctx) {
    if (run.state !== 'completed') return [];
    return [ctx.executeAction('house', 'send_report', {
      message: run.reportText.slice(0, 4096),
    }, { key: 'completion-notification' })];
  },
};
